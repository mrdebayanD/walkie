// One Durable Object per group ("server"). It holds the group's state, relays
// live audio over WebSockets (with hibernation, so idle groups cost nothing),
// stores clips, and runs SOS. Behaviour mirrors server/ (Go) and
// docs/PROTOCOL.md; the apps talk to it exactly as they talk to the Go server,
// with https://<host>/g/<groupId> as the server address.

import { DurableObject } from "cloudflare:workers";
import { pushAPNsAlert, pushAPNsPTT, pushEnabled, pushFCM, TokenGone, type PushMessage } from "./push";
import {
  COLORS, HttpError, addUnique, cleanName, cleanPhone, contains, errJson, json, newID, newInviteCode, newToken,
  normalizeCode, readJSON, remove, sha256hex, type Env,
} from "./util";

export const VERSION = "cloud-1";

type Role = "admin" | "member";
type Status = "pending" | "active";

interface Devices { apnsPtt?: string; apnsAlert?: string; apnsSandbox?: boolean; fcm?: string }
interface User {
  id: string; name: string; role: Role; status: Status; platform: string; phone?: string;
  createdAt: string; tokenHash: string; devices: Devices; inviteChannel?: string; inviteLabel?: string;
}
interface Channel { id: string; name: string; color: string; isDefault: boolean; kind?: "group" | "direct"; members: string[]; createdAt: string }
interface Invite { id: string; codeHash: string; channelId: string; label: string; admin: boolean; createdAt: string; expiresAt: string; usedBy?: string }
interface Clip { id: string; channelId: string; userId: string; mode: string; sosId?: string; startedAt: string; durationMs: number; bytes: number }
interface Location { lat: number; lng: number; accuracy: number; at: string }
interface SOS {
  id: string; userId: string; channelId: string; createdAt: string; state: "active" | "resolved";
  resolution?: string; resolvedAt?: string; resolvedBy?: string; location?: Location;
  recipients: string[]; acked: string[]; responders: string[]; clipIds: string[];
}
interface State {
  name: string; base: string; createdAt: string;
  users: Record<string, User>; channels: Record<string, Channel>; invites: Record<string, Invite>;
  clips: Record<string, Clip>; sos: Record<string, SOS>;
  settings: { clipTtlHours: number }; nextTx: number;
  sosRepeat: Record<string, { sent: number; next: number }>;
}

interface Att { cid: string; uid: string; name: string; duty: boolean; hello: boolean }

interface Floor {
  channel: string; userId: string; name: string; txId: number; mode: string; sosId?: string;
  started: string; deadline: number; lastAudio: number; ownerCid: string;
  chunks: Uint8Array[]; bytes: number; packets: number;
  buffer: Uint8Array[]; bufBytes: number;
}

const LIMITS: Record<string, number> = { hold: 120_000, handsfree: 60_000, sos: 15_000 };
const GRACE = 2_000, IDLE = 10_000, CATCHUP_MAX = 512 * 1024, CLIP_MAX = 1_900_000;
const SOS_EVERY = 30_000, SOS_MAX = 10;
const HOUR = 3_600_000;

const isDirect = (c: Channel) => c.kind === "direct";
const canSee = (c: Channel, u: User) => contains(c.members, u.id) || (u.role === "admin" && !isDirect(c));
const nowISO = () => new Date().toISOString();

function sortChannels(cs: Channel[]) {
  return cs.sort((a, b) => (a.isDefault !== b.isDefault ? (a.isDefault ? -1 : 1) : a.createdAt.localeCompare(b.createdAt)));
}

function countPackets(p: Uint8Array): number {
  let i = 0, n = 0;
  while (i < p.length) {
    if (p.length - i < 2) return 0;
    const l = (p[i] << 8) | p[i + 1];
    if (l === 0 || l > 1275 || p.length - i < 2 + l) return 0;
    i += 2 + l;
    n++;
  }
  return n;
}

export class Group extends DurableObject<Env> {
  s!: State;
  floors = new Map<string, Floor>();
  presence = new Map<string, string>(); // uid -> "online,duty" last broadcast
  lastPushed = new Map<string, number>();
  joinHits = new Map<string, number[]>();

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair('{"t":"ping"}', '{"t":"pong"}'));
    ctx.blockConcurrencyWhile(async () => {
      this.s = ((await ctx.storage.get<State>("state")) ?? null) as State;
      // Baseline presence after waking from hibernation (no broadcast).
      for (const ws of ctx.getWebSockets()) {
        const a = ws.deserializeAttachment() as Att | null;
        if (a?.hello) this.presence.set(a.uid, this.presenceOf(a.uid).join(","));
      }
    });
  }

  private get ready() { return !!this.s?.name; }
  private async save() { await this.ctx.storage.put("state", this.s); }

  // ------------------------------------------------------------------ console (RPC from the Worker)

  async init(name: string, base: string) {
    if (this.ready) throw new Error("already initialised");
    const t = nowISO();
    const def: Channel = { id: newID(), name, color: "green", isDefault: true, kind: "group", members: [], createdAt: t };
    this.s = {
      name, base, createdAt: t, users: {}, channels: { [def.id]: def }, invites: {}, clips: {}, sos: {},
      settings: { clipTtlHours: 24 }, nextTx: 1, sosRepeat: {},
    };
    await this.save();
    return this.createInvite(def.id, "Admin", true, base);
  }

  async summary() {
    if (!this.ready) return null;
    const users = Object.values(this.s.users);
    return {
      name: this.s.name,
      createdAt: this.s.createdAt,
      members: users.filter((u) => u.status === "active").length,
      pending: users.filter((u) => u.status === "pending").length,
      online: users.filter((u) => this.presenceOf(u.id)[0]).length,
      channels: Object.values(this.s.channels).filter((c) => !isDirect(c)).length,
      activeSos: Object.values(this.s.sos).filter((x) => x.state === "active").length,
    };
  }

  async adminView() {
    if (!this.ready) return null;
    const now = Date.now();
    const users = Object.values(this.s.users).map((u) => ({ ...this.userView(u), createdAt: u.createdAt, inviteLabel: u.inviteLabel ?? "" }));
    users.sort((a, b) => a.name.localeCompare(b.name));
    return {
      name: this.s.name,
      createdAt: this.s.createdAt,
      settings: this.s.settings,
      channels: sortChannels(Object.values(this.s.channels).filter((c) => !isDirect(c))).map((c) => this.channelView(c)),
      users,
      invites: Object.values(this.s.invites)
        .filter((i) => !i.usedBy && Date.parse(i.expiresAt) > now)
        .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
        .map((i) => ({ id: i.id, channelId: i.channelId, label: i.label, admin: i.admin, createdAt: i.createdAt, expiresAt: i.expiresAt })),
      sos: Object.values(this.s.sos).filter((x) => x.state === "active").map((x) => this.sosView(x)),
    };
  }

  async rename(name: string) {
    this.must();
    const n = cleanName(name, 30);
    this.s.name = n;
    const def = this.defaultChannel();
    if (def) def.name = n;
    await this.save();
    this.broadcast({ t: "channels_changed" });
  }

  async setTtl(hours: number) {
    this.must();
    if (!Number.isInteger(hours) || hours < 1 || hours > 168) throw new HttpError(400, "bad_ttl", "History must be kept 1 to 168 hours");
    this.s.settings.clipTtlHours = hours;
    await this.save();
    this.broadcast({ t: "settings_changed", settings: this.s.settings });
    await this.sweep();
    await this.schedule();
  }

  async consoleCreateChannel(name: string, color: string, memberIds: string[]) {
    this.must();
    const admins = Object.values(this.s.users).filter((u) => u.role === "admin" && u.status === "active").map((u) => u.id);
    const c = this.newChannel(cleanName(name, 30), color, [...admins, ...(memberIds ?? [])]);
    await this.save();
    this.broadcast({ t: "channels_changed" });
    return this.channelView(c);
  }

  async consoleUpdateChannel(id: string, name?: string, color?: string, memberIds?: string[]) {
    this.must();
    const c = this.s.channels[id];
    if (!c || isDirect(c)) throw new HttpError(404, "not_found", "Channel not found");
    if (name !== undefined) {
      c.name = cleanName(name, 30);
      if (c.isDefault) this.s.name = c.name;
    }
    if (color !== undefined && COLORS.includes(color)) c.color = color;
    if (memberIds) {
      const admins = Object.values(this.s.users).filter((u) => u.role === "admin").map((u) => u.id);
      c.members = [...new Set([...memberIds, ...admins])].filter((id) => this.s.users[id]?.status === "active");
    }
    await this.save();
    this.broadcast({ t: "channels_changed" });
    return this.channelView(c);
  }

  async consoleDeleteChannel(id: string) {
    this.must();
    this.deleteChannel(id);
    await this.save();
    this.broadcast({ t: "channels_changed" });
  }

  async createInvite(channelId: string, label: string, admin: boolean, base?: string) {
    this.must();
    if (base) this.s.base = base;
    const lbl = (label ?? "").trim();
    if ([...lbl].length > 60) throw new HttpError(400, "bad_label", "Label is too long");
    let ch = this.s.channels[channelId];
    if (!ch || isDirect(ch)) ch = this.defaultChannel()!;
    const now = Date.now();
    const open = Object.values(this.s.invites).filter((i) => !i.usedBy && Date.parse(i.expiresAt) > now).length;
    if (open >= 30) throw new HttpError(400, "limit", "Too many open invites. Delete some first.");
    const code = newInviteCode();
    const inv: Invite = {
      id: newID(), codeHash: await sha256hex(normalizeCode(code)), channelId: ch.id, label: lbl, admin: !!admin,
      createdAt: new Date(now).toISOString(), expiresAt: new Date(now + 24 * HOUR).toISOString(),
    };
    this.s.invites[inv.id] = inv;
    await this.save();
    await this.schedule();
    return {
      invite: { id: inv.id, channelId: inv.channelId, label: inv.label, admin: inv.admin, createdAt: inv.createdAt, expiresAt: inv.expiresAt },
      code, url: `${this.s.base}/join?c=${code}`, appUrl: this.appURL(code), channelName: ch.name,
    };
  }

  async deleteInvite(id: string) {
    this.must();
    if (!this.s.invites[id]) throw new HttpError(404, "not_found", "Invite not found");
    delete this.s.invites[id];
    await this.save();
  }

  async approve(id: string) {
    this.must();
    const u = this.approveUser(id);
    await this.save();
    this.broadcast({ t: "channels_changed" });
    return this.userView(u);
  }

  async removeUser(id: string) {
    this.must();
    await this.deleteUser(id);
    await this.save();
    this.broadcast({ t: "channels_changed" });
  }

  async consoleUpdateUser(id: string, body: { name?: string; phone?: string; role?: string }) {
    this.must();
    const u = this.s.users[id];
    if (!u) throw new HttpError(404, "not_found", "Person not found");
    if (body.name !== undefined) u.name = cleanName(body.name, 40);
    if (body.phone !== undefined) u.phone = cleanPhone(body.phone);
    if (body.role === "admin" || body.role === "member") {
      if (body.role === "member" && u.role === "admin" && Object.values(this.s.users).filter((x) => x.role === "admin").length <= 1) {
        throw new HttpError(400, "last_admin", "A group needs at least one admin on a phone");
      }
      u.role = body.role;
    }
    await this.save();
    this.broadcast({ t: "channels_changed" });
    for (const ws of this.socketsOf(id)) this.send(ws, { t: "users_changed" });
    return this.userView(u);
  }

  async destroy() {
    for (const ws of this.ctx.getWebSockets()) {
      try { ws.close(4001, "group deleted"); } catch {}
    }
    await this.ctx.storage.deleteAlarm();
    await this.ctx.storage.deleteAll();
    this.s = null as unknown as State;
  }

  private must() {
    if (!this.ready) throw new HttpError(404, "no_group", "This group doesn't exist");
  }

  // ------------------------------------------------------------------ HTTP from the apps

  async fetch(req: Request): Promise<Response> {
    try {
      if (!this.ready) return errJson(404, "no_group", "This group doesn't exist (it may have been deleted).");
      const base = req.headers.get("x-hollo-base");
      if (base && base !== this.s.base) this.s.base = base; // saved with the next change
      return await this.route(req);
    } catch (e) {
      if (e instanceof HttpError) return errJson(e.status, e.code, e.text);
      console.error("group error", e);
      return errJson(500, "internal", "Something went wrong on the server");
    }
  }

  private async route(req: Request): Promise<Response> {
    const url = new URL(req.url);
    const p = url.pathname.replace(/\/+$/, "") || "/";
    const m = req.method;
    const seg = p.split("/").filter(Boolean); // ["v1", ...]

    if (p === "/healthz") return new Response("ok");
    if (m === "POST" && p === "/v1/join") return this.join(req);
    if (seg[0] !== "v1") return errJson(404, "not_found", "Not found");

    const user = await this.auth(req);
    const active = () => { if (user.status !== "active") throw new HttpError(403, "pending", "Waiting for the admin to approve you"); };
    const admin = () => { active(); if (user.role !== "admin") throw new HttpError(403, "forbidden", "Only the admin can do this"); };
    const [, a, id, b] = seg;

    switch (`${m} ${a}${b ? "/:/" + b : id ? "/:" : ""}`) {
      case "GET me": return json({ user: this.userView(user), settings: this.s.settings, server: { version: VERSION, ...this.pushFlags(), group: this.s.name } });
      case "PUT me": return this.updateMe(req, user);
      case "PUT me/:": if (id === "push") return this.pushTokens(req, user); break;
      case "GET ws": active(); return this.acceptWS(req, user);
      case "GET users": active(); return json({ users: this.listUsers(user) });
      case "POST users/:/approve": admin(); { const u = this.approveUser(id); await this.save(); this.broadcast({ t: "channels_changed" }); return json({ user: this.userView(u) }); }
      case "DELETE users/:": admin(); if (id === user.id) throw new HttpError(400, "self", "You can't remove yourself"); await this.deleteUser(id); await this.save(); this.broadcast({ t: "channels_changed" }); return json({ ok: true });
      case "PUT users/:": admin(); return this.updateUser(req, id);
      case "GET channels": active(); return json({ channels: sortChannels(Object.values(this.s.channels).filter((c) => canSee(c, user))).map((c) => this.channelView(c)) });
      case "POST channels": admin(); return this.createChannel(req, user);
      case "PATCH channels/:": admin(); return this.patchChannel(req, id);
      case "PUT channels/:/members": admin(); return this.setMembers(req, id, user);
      case "DELETE channels/:": active(); return this.deleteChannelReq(id, user);
      case "POST direct": active(); return this.direct(req, user);
      case "GET channels/:/clips": active(); return this.listClips(id, user);
      case "GET clips/:/audio": active(); return this.clipAudio(id, user);
      case "POST invites": admin(); { const body = await readJSON(req); const r = await this.createInvite(body.channelId, body.label, false); return json(r); }
      case "GET invites": admin(); return json({ invites: (await this.adminView())!.invites.filter((i) => !i.admin) });
      case "DELETE invites/:": admin(); await this.deleteInvite(id); return json({ ok: true });
      case "GET settings": active(); return json({ settings: this.s.settings });
      case "PUT settings": admin(); { const body = await readJSON(req); await this.setTtl(body.clipTtlHours); return json({ settings: this.s.settings }); }
      case "POST sos": active(); return this.createSOS(req, user);
      case "GET sos": active(); return this.listSOS(user);
      case "POST sos/:/location": active(); return this.sosLocation(req, id, user);
      case "POST sos/:/ack": active(); return this.mutateSOS(id, user, (x) => { this.mustRecipient(x, user); x.acked = addUnique(x.acked, user.id); });
      case "POST sos/:/respond": active(); return this.mutateSOS(id, user, (x) => { this.mustRecipient(x, user); x.acked = addUnique(x.acked, user.id); x.responders = addUnique(x.responders, user.id); });
      case "POST sos/:/resolve": active(); return this.resolveSOS(req, id, user);
      case "DELETE sos/:": admin(); return this.deleteSOS(id);
    }
    return errJson(404, "not_found", "Not found");
  }

  private pushFlags() {
    const p = pushEnabled(this.env);
    return { pushIos: p.ios, pushAndroid: p.android };
  }

  private appURL(code: string) {
    return `walkie://join?s=${encodeURIComponent(this.s.base)}&c=${encodeURIComponent(code)}`;
  }

  private async auth(req: Request): Promise<User> {
    const h = req.headers.get("authorization") ?? "";
    if (!h.startsWith("Bearer ") || h.length < 10) throw new HttpError(401, "unauthorized", "Missing token");
    const th = await sha256hex(h.slice(7));
    const u = Object.values(this.s.users).find((x) => x.tokenHash === th);
    if (!u) throw new HttpError(401, "unauthorized", "Unknown or removed device");
    return u;
  }

  private defaultChannel() { return Object.values(this.s.channels).find((c) => c.isDefault); }

  private userView(u: User) {
    const [online, duty] = this.presenceOf(u.id);
    const v: Record<string, unknown> = { id: u.id, name: u.name, role: u.role, status: u.status, platform: u.platform, online, duty };
    if (u.phone) v.phone = u.phone;
    return v as { id: string; name: string; role: Role; status: Status; platform: string; online: boolean; duty: boolean; phone?: string };
  }

  private channelView(c: Channel) {
    return {
      id: c.id, name: c.name, color: c.color, isDefault: c.isDefault, kind: c.kind ?? "group",
      members: c.members.map((id) => this.s.users[id]).filter(Boolean).map((u) => this.userView(u!)),
    };
  }

  // ---- join ----

  private async join(req: Request): Promise<Response> {
    const ip = req.headers.get("cf-connecting-ip") ?? "?";
    const now = Date.now();
    const hits = (this.joinHits.get(ip) ?? []).filter((t) => now - t < 60_000);
    if (hits.length >= 10) return errJson(429, "rate_limited", "Too many attempts. Try again in a minute.");
    hits.push(now);
    this.joinHits.set(ip, hits);
    if (this.joinHits.size > 5000) this.joinHits.clear();

    const body = await readJSON(req);
    const name = cleanName(body.name, 40);
    const phone = cleanPhone(body.phone);
    if (body.platform !== "ios" && body.platform !== "android") throw new HttpError(400, "bad_platform", "platform must be ios or android");
    const h = await sha256hex(normalizeCode(String(body.code ?? "")));
    const inv = Object.values(this.s.invites).find((i) => i.codeHash === h);
    if (!inv) return errJson(404, "invalid_invite", "This invite code isn't right for this group. Check the link or ask for a new one.");
    if (inv.usedBy) return errJson(404, "invite_used", "This invite has already been used (each invite works once). Ask the admin for a new one.");
    if (Date.parse(inv.expiresAt) < now) return errJson(404, "invite_expired", "This invite has expired (invites last 24 hours). Ask the admin for a new one.");

    const token = newToken();
    const u: User = {
      id: newID(), name, role: "member", status: "pending", platform: body.platform, phone: phone || undefined,
      createdAt: nowISO(), tokenHash: await sha256hex(token), devices: {}, inviteChannel: inv.channelId, inviteLabel: inv.label,
    };
    if (inv.admin) {
      u.role = "admin";
      u.status = "active";
      // Admins are in every group channel.
      for (const c of Object.values(this.s.channels)) if (!isDirect(c)) c.members = addUnique(c.members, u.id);
    }
    inv.usedBy = u.id;
    this.s.users[u.id] = u;
    await this.save();
    if (u.status === "pending") {
      const admins = Object.values(this.s.users).filter((x) => x.role === "admin" && x.id !== u.id).map((x) => x.id);
      this.sendTo(admins, { t: "users_changed" });
    } else this.broadcast({ t: "channels_changed" });
    return json({ token, user: this.userView(u) });
  }

  // ---- me / users ----

  private async updateMe(req: Request, u: User) {
    const body = await readJSON(req);
    if (body.name !== undefined) u.name = cleanName(body.name, 40);
    if (body.phone !== undefined) u.phone = cleanPhone(body.phone) || undefined;
    await this.save();
    this.broadcast({ t: "channels_changed" });
    return json({ user: this.userView(u) });
  }

  private async pushTokens(req: Request, u: User) {
    const body = await readJSON(req);
    for (const k of ["apnsPtt", "apnsAlert", "fcm"] as const) {
      if (body[k] !== undefined) {
        if (typeof body[k] !== "string" || body[k].length > 4096) throw new HttpError(400, "bad_request", "Token too long");
        u.devices[k] = body[k];
      }
    }
    if (typeof body.apnsSandbox === "boolean") u.devices.apnsSandbox = body.apnsSandbox;
    await this.save();
    return json({ ok: true });
  }

  private listUsers(u: User) {
    return Object.values(this.s.users)
      .filter((x) => x.status === "active" || u.role === "admin")
      .map((x) => this.userView(x))
      .sort((a, b) => (a.status !== b.status ? (a.status === "pending" ? -1 : 1) : a.name.localeCompare(b.name)));
  }

  private approveUser(id: string): User {
    const x = this.s.users[id];
    if (!x) throw new HttpError(404, "not_found", "Person not found");
    x.status = "active";
    const ch = this.s.channels[x.inviteChannel ?? ""] ?? this.defaultChannel();
    if (ch) ch.members = addUnique(ch.members, x.id);
    return x;
  }

  private async deleteUser(id: string) {
    if (!this.s.users[id]) throw new HttpError(404, "not_found", "Person not found");
    for (const [cid, c] of Object.entries(this.s.channels)) {
      if (isDirect(c) && contains(c.members, id)) this.deleteChannel(cid, true);
      else c.members = remove(c.members, id);
    }
    delete this.s.users[id];
    for (const ws of this.socketsOf(id)) {
      try { ws.close(4003, "removed"); } catch {}
    }
  }

  private async updateUser(req: Request, id: string) {
    const body = await readJSON(req);
    const x = this.s.users[id];
    if (!x) throw new HttpError(404, "not_found", "Person not found");
    if (body.name !== undefined) x.name = cleanName(body.name, 40);
    if (body.phone !== undefined) x.phone = cleanPhone(body.phone) || undefined;
    await this.save();
    this.broadcast({ t: "channels_changed" });
    return json({ user: this.userView(x) });
  }

  // ---- channels ----

  private newChannel(name: string, color: string, memberIds: string[]): Channel {
    const groups = Object.values(this.s.channels).filter((c) => !isDirect(c)).length;
    if (groups >= 20) throw new HttpError(400, "limit", "This group already has 20 channels");
    const members = [...new Set(memberIds)].filter((id) => this.s.users[id]?.status === "active");
    const c: Channel = { id: newID(), name, color: COLORS.includes(color) ? color : "blue", isDefault: false, kind: "group", members, createdAt: nowISO() };
    this.s.channels[c.id] = c;
    return c;
  }

  private async createChannel(req: Request, u: User) {
    const body = await readJSON(req);
    const c = this.newChannel(cleanName(body.name, 30), body.color, [u.id, ...(Array.isArray(body.memberIds) ? body.memberIds : [])]);
    await this.save();
    this.broadcast({ t: "channels_changed" });
    return json({ channel: this.channelView(c) });
  }

  private async patchChannel(req: Request, id: string) {
    const body = await readJSON(req);
    const c = await this.consoleUpdateChannel(id, body.name, body.color);
    return json({ channel: c });
  }

  private async setMembers(req: Request, id: string, u: User) {
    const body = await readJSON(req);
    const c = this.s.channels[id];
    if (!c || isDirect(c)) throw new HttpError(404, "not_found", "Channel not found");
    const ids: string[] = Array.isArray(body.memberIds) ? body.memberIds : [];
    c.members = [...new Set([...ids, u.id])].filter((x) => this.s.users[x]?.status === "active");
    await this.save();
    this.broadcast({ t: "channels_changed" });
    return json({ channel: this.channelView(c) });
  }

  private deleteChannel(id: string, includeSOSClips = false) {
    const c = this.s.channels[id];
    if (!c) throw new HttpError(404, "not_found", "Channel not found");
    if (c.isDefault) throw new HttpError(400, "default_channel", "The main channel can't be deleted");
    delete this.s.channels[id];
    for (const [cid, cl] of Object.entries(this.s.clips)) {
      if (cl.channelId === id && (includeSOSClips || !cl.sosId)) {
        delete this.s.clips[cid];
        this.ctx.storage.delete("clip:" + cid);
      }
    }
    this.endFloor(id, "released");
  }

  private async deleteChannelReq(id: string, u: User) {
    const c = this.s.channels[id];
    if (!c || !canSee(c, u)) throw new HttpError(404, "not_found", "Channel not found");
    if (!isDirect(c) && u.role !== "admin") throw new HttpError(403, "forbidden", "Only the admin can do this");
    this.deleteChannel(id);
    await this.save();
    this.broadcast({ t: "channels_changed" });
    return json({ ok: true });
  }

  private async direct(req: Request, u: User) {
    const body = await readJSON(req);
    if (!body.userId) throw new HttpError(400, "bad_request", "Invalid request");
    if (body.userId === u.id) throw new HttpError(400, "self", "You can't open a personal channel with yourself");
    const other = this.s.users[body.userId];
    if (!other || other.status !== "active") throw new HttpError(404, "not_found", "That person isn't in the group");
    let c = Object.values(this.s.channels).find((x) => isDirect(x) && x.members.length === 2 && contains(x.members, u.id) && contains(x.members, other.id));
    if (!c) {
      c = { id: newID(), name: "", color: "purple", isDefault: false, kind: "direct", members: [u.id, other.id], createdAt: nowISO() };
      this.s.channels[c.id] = c;
      await this.save();
      this.sendTo([u.id, other.id], { t: "channels_changed" });
    }
    return json({ channel: this.channelView(c) });
  }

  // ---- clips ----

  private listClips(cid: string, u: User) {
    const c = this.s.channels[cid];
    if (!c || !canSee(c, u)) throw new HttpError(404, "not_found", "Channel not found");
    const ttl = this.s.settings.clipTtlHours * HOUR;
    const out = Object.values(this.s.clips)
      .filter((cl) => cl.channelId === cid)
      .map((cl) => {
        const v: Record<string, unknown> = {
          id: cl.id, channelId: cl.channelId, userId: cl.userId, userName: this.s.users[cl.userId]?.name ?? "Removed",
          mode: cl.mode, startedAt: cl.startedAt, durationMs: cl.durationMs,
        };
        if (cl.sosId) v.sosId = cl.sosId;
        else v.expiresAt = new Date(Date.parse(cl.startedAt) + ttl).toISOString();
        return v;
      })
      .sort((a, b) => String(b.startedAt).localeCompare(String(a.startedAt)))
      .slice(0, 500);
    return json({ clips: out });
  }

  private async clipAudio(id: string, u: User) {
    const cl = this.s.clips[id];
    let allowed = false;
    if (cl) {
      const c = this.s.channels[cl.channelId];
      if (c && canSee(c, u)) allowed = true;
      if (cl.sosId && contains(this.s.sos[cl.sosId]?.recipients ?? [], u.id)) allowed = true;
    }
    const data = allowed ? await this.ctx.storage.get<ArrayBuffer>("clip:" + id) : undefined;
    if (!data) throw new HttpError(404, "not_found", "Clip not found");
    return new Response(data, { headers: { "content-type": "application/octet-stream", "cache-control": "private, max-age=3600" } });
  }

  // ---- SOS ----

  private sosView(x: SOS) {
    return { ...x, recipients: [...x.recipients], acked: [...x.acked], responders: [...x.responders], clipIds: [...x.clipIds], userName: this.s.users[x.userId]?.name ?? "Removed" };
  }

  private broadcastSOS(id: string) {
    const x = this.s.sos[id];
    if (x) this.sendTo([...x.recipients, x.userId], { t: "sos", sos: this.sosView(x) });
  }

  private validLoc(lat: unknown, lng: unknown, acc: unknown) {
    return typeof lat === "number" && typeof lng === "number" && typeof acc === "number" && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180 && acc >= 0 && acc < 1e6;
  }

  private async createSOS(req: Request, u: User) {
    const body = await readJSON(req);
    const existing = Object.values(this.s.sos).find((x) => x.userId === u.id && x.state === "active");
    if (existing) return json({ sos: this.sosView(existing) });
    const def = this.defaultChannel()!;
    const x: SOS = {
      id: newID(), userId: u.id, channelId: def.id, createdAt: nowISO(), state: "active",
      recipients: def.members.filter((m) => m !== u.id && this.s.users[m]?.status === "active"),
      acked: [], responders: [], clipIds: [],
    };
    const acc = typeof body.accuracy === "number" ? body.accuracy : 0;
    if (this.validLoc(body.lat, body.lng, acc)) x.location = { lat: body.lat, lng: body.lng, accuracy: acc, at: nowISO() };
    this.s.sos[x.id] = x;
    this.s.sosRepeat[x.id] = { sent: 0, next: Date.now() };
    await this.save();
    console.log(`SOS ${x.id} from ${u.id}`);
    this.broadcastSOS(x.id);
    await this.sosTick();
    await this.schedule();
    return json({ sos: this.sosView(x) });
  }

  private listSOS(u: User) {
    const out = Object.values(this.s.sos)
      .filter((x) => x.userId === u.id || contains(x.recipients, u.id) || u.role === "admin")
      .map((x) => this.sosView(x))
      .sort((a, b) => (a.state !== b.state ? (a.state === "active" ? -1 : 1) : b.createdAt.localeCompare(a.createdAt)));
    return json({ sos: out });
  }

  private mustRecipient(x: SOS, u: User) {
    if (!contains(x.recipients, u.id)) throw new HttpError(403, "sos_error", "Not a recipient");
  }

  private async mutateSOS(id: string, u: User, fn: (x: SOS) => void) {
    const x = this.s.sos[id];
    if (!x) throw new HttpError(404, "sos_error", "SOS not found");
    fn(x);
    await this.save();
    this.broadcastSOS(id);
    return json({ sos: this.sosView(x) });
  }

  private async sosLocation(req: Request, id: string, u: User) {
    const body = await readJSON(req);
    if (!this.validLoc(body.lat, body.lng, body.accuracy)) throw new HttpError(400, "bad_request", "Invalid location");
    return this.mutateSOS(id, u, (x) => {
      if (x.userId !== u.id) throw new HttpError(403, "sos_error", "Only the sender can update the location");
      if (x.state !== "active") throw new HttpError(409, "sos_error", "This SOS is over");
      x.location = { lat: body.lat, lng: body.lng, accuracy: body.accuracy, at: nowISO() };
    });
  }

  private async resolveSOS(req: Request, id: string, u: User) {
    const body = await readJSON(req);
    if (body.resolution !== "safe" && body.resolution !== "false_alarm") throw new HttpError(400, "bad_request", "resolution must be safe or false_alarm");
    const r = await this.mutateSOS(id, u, (x) => {
      if (x.userId !== u.id && u.role !== "admin") throw new HttpError(403, "sos_error", "Only the sender or the admin can close an SOS");
      if (x.state !== "active") throw new HttpError(409, "sos_error", "Already closed");
      x.state = "resolved";
      x.resolution = body.resolution;
      x.resolvedAt = nowISO();
      x.resolvedBy = u.id;
      delete this.s.sosRepeat[x.id];
    });
    const x = this.s.sos[id];
    const sender = this.s.users[x.userId]?.name ?? "";
    for (const rid of x.recipients) {
      const t = this.s.users[rid];
      if (t) this.sendPush(t, { kind: "sos_resolved", sosId: x.id, speaker: sender, resolution: x.resolution });
    }
    return r;
  }

  private async deleteSOS(id: string) {
    const x = this.s.sos[id];
    if (!x) throw new HttpError(404, "not_found", "SOS not found");
    for (const cid of x.clipIds) {
      delete this.s.clips[cid];
      await this.ctx.storage.delete("clip:" + cid);
    }
    delete this.s.sos[id];
    delete this.s.sosRepeat[id];
    await this.save();
    return json({ ok: true });
  }

  private async sosTick() {
    const now = Date.now();
    let changed = false;
    for (const [id, r] of Object.entries(this.s.sosRepeat)) {
      if (r.next > now) continue;
      const x = this.s.sos[id];
      const targets = x && x.state === "active" ? x.recipients.filter((rid) => !contains(x.acked, rid)).map((rid) => this.s.users[rid]).filter(Boolean) as User[] : [];
      const rounds = r.sent + 1;
      changed = true;
      if (!targets.length || rounds > SOS_MAX) {
        delete this.s.sosRepeat[id];
        continue;
      }
      r.sent = rounds;
      r.next = now + SOS_EVERY;
      const sender = this.s.users[x!.userId]?.name ?? "";
      for (const t of targets) this.sendPush(t, { kind: "sos", sosId: id, speaker: sender, repeat: rounds > 1 });
    }
    if (changed) await this.save();
  }

  // ---- push ----

  private sendPush(u: User, m: PushMessage) {
    const p = pushEnabled(this.env);
    const go = async () => {
      try {
        if (u.platform === "ios" && p.ios) {
          if (m.kind === "tx") {
            if (u.devices.apnsPtt) await pushAPNsPTT(this.env, u.devices.apnsPtt, !!u.devices.apnsSandbox, m);
          } else if (u.devices.apnsAlert) await pushAPNsAlert(this.env, u.devices.apnsAlert, !!u.devices.apnsSandbox, m);
        } else if (u.platform === "android" && p.android && u.devices.fcm) {
          await pushFCM(this.env, u.devices.fcm, m);
        }
      } catch (e) {
        if (e instanceof TokenGone) {
          const x = this.s.users[u.id];
          if (x) {
            if (u.platform === "android") delete x.devices.fcm;
            else if (m.kind === "tx") delete x.devices.apnsPtt;
            else delete x.devices.apnsAlert;
            await this.save();
          }
        } else console.error(`push to ${u.id} failed:`, String(e));
      }
    };
    this.ctx.waitUntil(go());
  }

  // ------------------------------------------------------------------ WebSocket relay

  private acceptWS(req: Request, u: User): Response {
    if (req.headers.get("upgrade")?.toLowerCase() !== "websocket") return errJson(426, "upgrade_required", "WebSocket required");
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.ctx.acceptWebSocket(server, [u.id]);
    server.serializeAttachment({ cid: newID(), uid: u.id, name: u.name, duty: false, hello: false } satisfies Att);
    return new Response(null, { status: 101, webSocket: client });
  }

  private att(ws: WebSocket): Att { return ws.deserializeAttachment() as Att; }

  private socketsOf(uid: string): WebSocket[] {
    return this.ctx.getWebSockets(uid).filter((ws) => this.att(ws)?.hello);
  }

  private presenceOf(uid: string): [boolean, boolean] {
    const ss = this.ready ? this.socketsOf(uid) : [];
    return [ss.length > 0, ss.some((ws) => this.att(ws).duty)];
  }

  private send(ws: WebSocket, v: unknown) {
    try { ws.send(typeof v === "string" || v instanceof Uint8Array ? (v as any) : JSON.stringify(v)); } catch {}
  }

  private sendTo(uids: string[], v: unknown) {
    const msg = JSON.stringify(v);
    for (const uid of new Set(uids)) for (const ws of this.socketsOf(uid)) this.send(ws, msg);
  }

  private broadcast(v: unknown) {
    const msg = JSON.stringify(v);
    for (const ws of this.ctx.getWebSockets()) if (this.att(ws)?.hello) this.send(ws, msg);
  }

  private coMembers(uid: string): string[] {
    const out = new Set<string>();
    for (const c of Object.values(this.s.channels)) if (contains(c.members, uid)) c.members.forEach((m) => m !== uid && out.add(m));
    return [...out];
  }

  private refreshPresence(uid: string) {
    const [online, duty] = this.presenceOf(uid);
    const key = `${online},${duty}`;
    if (this.presence.get(uid) === key) return;
    this.presence.set(uid, key);
    this.sendTo(this.coMembers(uid), { t: "presence", userId: uid, online, duty });
  }

  private floorTaken(f: Floor) {
    return { t: "floor_taken", channel: f.channel, txId: f.txId, userId: f.userId, name: f.name, mode: f.mode, startedAt: f.started };
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer) {
    if (!this.ready) { try { ws.close(4001, "group deleted"); } catch {} return; }
    const a = this.att(ws);
    if (!a) return;
    if (typeof message !== "string") {
      if (a.hello) this.audio(ws, a, new Uint8Array(message));
      return;
    }
    let m: any;
    try { m = JSON.parse(message); } catch { return; }
    if (!a.hello) {
      if (m?.t !== "hello") { try { ws.close(1008, "hello required"); } catch {} return; }
      a.hello = true;
      a.duty = m.duty === true;
      ws.serializeAttachment(a);
      this.welcome(ws, a);
      return;
    }
    switch (m?.t) {
      case "ping": this.send(ws, { t: "pong" }); break;
      case "duty":
      case "hello": {
        const on = m.t === "duty" ? m.on : m.duty;
        if (typeof on === "boolean") { a.duty = on; ws.serializeAttachment(a); this.refreshPresence(a.uid); }
        break;
      }
      case "floor_request": await this.requestFloor(ws, a, String(m.channel ?? ""), String(m.mode ?? ""), m.sosId ? String(m.sosId) : undefined); break;
      case "floor_release": await this.releaseFloor(a); break;
    }
  }

  async webSocketClose(ws: WebSocket, code: number) {
    await this.dropSocket(ws);
    try { ws.close(code === 1005 || code === 1006 ? 1000 : code, ""); } catch {}
  }

  async webSocketError(ws: WebSocket) {
    await this.dropSocket(ws);
  }

  private async dropSocket(ws: WebSocket) {
    if (!this.ready) return;
    const a = this.att(ws);
    if (!a) return;
    a.hello = false; // excluded from presence from now on
    try { ws.serializeAttachment(a); } catch {}
    for (const [ch, f] of this.floors) if (f.ownerCid === a.cid) await this.endFloor(ch, "disconnected");
    this.refreshPresence(a.uid);
  }

  private welcome(ws: WebSocket, a: Att) {
    const mine = [...this.floors.values()].filter((f) => contains(this.s.channels[f.channel]?.members ?? [], a.uid));
    this.send(ws, { t: "welcome", userId: a.uid, active: mine.map((f) => ({ channel: f.channel, txId: f.txId, userId: f.userId, startedAt: f.started })) });
    for (const f of mine) {
      this.send(ws, this.floorTaken(f));
      if (a.duty && f.userId !== a.uid) for (const b of f.buffer) this.send(ws, b);
    }
    this.refreshPresence(a.uid);
  }

  private async requestFloor(ws: WebSocket, a: Att, channel: string, mode: string, sosId?: string) {
    const limit = LIMITS[mode];
    if (!limit) return this.send(ws, { t: "floor_denied", channel, reason: "bad_request" });
    const c = this.s.channels[channel];
    let ok = !!c && contains(c.members, a.uid);
    if (mode === "sos") {
      const x = sosId ? this.s.sos[sosId] : undefined;
      if (!x || x.userId !== a.uid || x.state !== "active" || x.channelId !== channel) ok = false;
    }
    if (!ok) return this.send(ws, { t: "floor_denied", channel, reason: "not_member" });
    for (const [ch, f] of this.floors) if (f.ownerCid === a.cid && ch !== channel) await this.endFloor(ch, "released");
    const cur = this.floors.get(channel);
    if (cur) {
      if (cur.ownerCid === a.cid) return this.send(ws, { t: "floor_granted", channel, txId: cur.txId, maxMs: Math.max(0, cur.deadline - Date.now()) });
      if (mode !== "sos") return this.send(ws, { t: "floor_denied", channel, reason: "busy", holder: cur.userId });
      await this.endFloor(channel, "preempted");
    }
    const txId = this.s.nextTx;
    this.s.nextTx = this.s.nextTx >= 0xffffffff ? 1 : this.s.nextTx + 1;
    const now = Date.now();
    const name = this.s.users[a.uid]?.name ?? a.name;
    const f: Floor = {
      channel, userId: a.uid, name, txId, mode, sosId, started: new Date(now).toISOString(),
      deadline: now + limit + GRACE, lastAudio: now, ownerCid: a.cid, chunks: [], bytes: 0, packets: 0, buffer: [], bufBytes: 0,
    };
    this.floors.set(channel, f);
    this.send(ws, { t: "floor_granted", channel, txId, maxMs: limit });

    const taken = JSON.stringify(this.floorTaken(f));
    const others: string[] = [], offline = new Set<string>();
    for (const uid of c!.members) {
      if (uid === a.uid) continue;
      others.push(uid);
      const ss = this.socketsOf(uid);
      if (!ss.length) offline.add(uid);
      for (const s of ss) this.send(s, taken);
    }
    await this.save(); // nextTx
    await this.schedule();
    // Wake phones: iPhones only when not connected; Android always (Doze can
    // freeze a socket that still looks connected).
    for (const uid of others) {
      const u = this.s.users[uid];
      if (!u || u.status !== "active") continue;
      if (u.platform !== "android" && !offline.has(uid)) continue;
      const last = this.lastPushed.get(uid) ?? 0;
      if (now - last < 3000) continue;
      this.lastPushed.set(uid, now);
      this.sendPush(u, { kind: "tx", channel, txId, speaker: name });
    }
  }

  private async releaseFloor(a: Att) {
    for (const [ch, f] of this.floors) if (f.ownerCid === a.cid) await this.endFloor(ch, "released");
  }

  private audio(ws: WebSocket, a: Att, data: Uint8Array) {
    if (data.length < 3 || data[0] !== 0x01) return;
    const payload = data.subarray(1);
    const n = countPackets(payload);
    if (!n || n > 10) return;
    let f: Floor | undefined;
    for (const x of this.floors.values()) if (x.ownerCid === a.cid) { f = x; break; }
    if (!f) return;
    const now = Date.now();
    f.lastAudio = now;
    if (now > f.deadline) { this.endFloor(f.channel, "timeout"); return; }
    if (f.bytes + payload.length <= CLIP_MAX) {
      f.chunks.push(payload.slice());
      f.bytes += payload.length;
      f.packets += n;
    }
    const out = new Uint8Array(5 + payload.length);
    out[0] = 0x01;
    new DataView(out.buffer).setUint32(1, f.txId);
    out.set(payload, 5);
    if (f.bufBytes + out.length <= CATCHUP_MAX) { f.buffer.push(out); f.bufBytes += out.length; }
    for (const uid of this.s.channels[f.channel]?.members ?? []) {
      if (uid === a.uid) continue;
      for (const s of this.socketsOf(uid)) if (this.att(s).duty) this.send(s, out);
    }
  }

  private async endFloor(channel: string, reason: string) {
    const f = this.floors.get(channel);
    if (!f) return;
    this.floors.delete(channel);
    const dur = f.packets * 20;
    let clipId = "";
    if (f.packets > 0) {
      clipId = String(f.txId);
      const file = new Uint8Array(10 + f.bytes);
      file.set([0x57, 0x4b, 0x43, 0x31], 0); // "WKC1"
      const dv = new DataView(file.buffer);
      dv.setUint32(4, 48000);
      dv.setUint16(8, 20);
      let o = 10;
      for (const ch of f.chunks) { file.set(ch, o); o += ch.length; }
      await this.ctx.storage.put("clip:" + clipId, file.buffer);
      this.s.clips[clipId] = {
        id: clipId, channelId: f.channel, userId: f.userId, mode: f.mode, sosId: f.sosId,
        startedAt: f.started, durationMs: dur, bytes: file.length,
      };
      if (f.sosId && this.s.sos[f.sosId]) this.s.sos[f.sosId].clipIds = addUnique(this.s.sos[f.sosId].clipIds, clipId);
      await this.save();
    }
    const msg = { t: "floor_ended", channel: f.channel, txId: f.txId, userId: f.userId, clipId, durationMs: dur, reason };
    const members = this.s.channels[f.channel]?.members ?? [];
    this.sendTo(contains(members, f.userId) ? members : [...members, f.userId], msg);
    if (f.sosId && clipId) this.broadcastSOS(f.sosId);
    await this.schedule();
  }

  // ------------------------------------------------------------------ timers (one alarm)

  private nextSweep(): number {
    const ttl = this.s.settings.clipTtlHours * HOUR;
    let next = Infinity;
    for (const cl of Object.values(this.s.clips)) if (!cl.sosId) next = Math.min(next, Date.parse(cl.startedAt) + ttl);
    for (const i of Object.values(this.s.invites)) next = Math.min(next, Date.parse(i.expiresAt) + 24 * HOUR);
    return next;
  }

  private async schedule() {
    if (!this.ready) return;
    let next = this.nextSweep();
    for (const r of Object.values(this.s.sosRepeat)) next = Math.min(next, r.next);
    if (this.floors.size) next = Math.min(next, Date.now() + 1000);
    if (next === Infinity) return;
    const cur = await this.ctx.storage.getAlarm();
    const at = Math.max(next, Date.now() + 200);
    if (cur === null || cur > at || cur < Date.now()) await this.ctx.storage.setAlarm(at);
  }

  async alarm() {
    if (!this.ready) return;
    const now = Date.now();
    for (const [ch, f] of this.floors) {
      if (now > f.deadline) await this.endFloor(ch, "timeout");
      else if (now - f.lastAudio > IDLE) await this.endFloor(ch, "idle");
    }
    await this.sosTick();
    await this.sweep();
    await this.ctx.storage.deleteAlarm();
    await this.schedule();
  }

  private async sweep() {
    const now = Date.now();
    const ttl = this.s.settings.clipTtlHours * HOUR;
    let changed = false;
    for (const [id, cl] of Object.entries(this.s.clips)) {
      if (!cl.sosId && now - Date.parse(cl.startedAt) > ttl) {
        delete this.s.clips[id];
        await this.ctx.storage.delete("clip:" + id);
        changed = true;
      }
    }
    for (const [id, i] of Object.entries(this.s.invites)) {
      if (now > Date.parse(i.expiresAt) + 24 * HOUR) { delete this.s.invites[id]; changed = true; }
    }
    if (changed) await this.save();
  }
}
