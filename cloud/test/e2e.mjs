// End-to-end test against `wrangler dev` (BASE defaults to http://localhost:8787).
import WebSocket from "ws";
const BASE = process.env.BASE || "http://localhost:8787";
let cookie = "", fails = 0;
const ok = (c, msg) => { console.log((c ? "  ok  " : "  FAIL") + " " + msg); if (!c) fails++; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function api(method, path, body, { token, raw } = {}) {
  const h = { "x-hollo": "1" };
  if (body !== undefined) h["content-type"] = "application/json";
  if (token) h.authorization = "Bearer " + token;
  if (cookie) h.cookie = cookie;
  const r = await fetch(BASE + path, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body), redirect: "manual" });
  const sc = r.headers.get("set-cookie");
  if (sc) cookie = sc.split(";")[0];
  if (raw) return r;
  const t = await r.text();
  let j; try { j = JSON.parse(t); } catch { j = { text: t }; }
  return { status: r.status, ...j };
}

function client(url, token, duty = true) {
  const ws = new WebSocket(url.replace(/^http/, "ws") + "/v1/ws", { headers: { authorization: "Bearer " + token } });
  const msgs = [], bins = [];
  ws.on("message", (d, isBin) => { if (isBin) bins.push(new Uint8Array(d)); else msgs.push(JSON.parse(d.toString())); });
  const opened = new Promise((r, j) => { ws.on("open", r); ws.on("error", j); });
  return {
    ws, msgs, bins,
    async start() { await opened; ws.send(JSON.stringify({ t: "hello", duty, app: "test" })); await this.wait("welcome"); },
    send(v) { ws.send(JSON.stringify(v)); },
    async wait(t, pred = () => true, ms = 15000) {
      const end = Date.now() + ms;
      while (Date.now() < end) {
        const i = msgs.findIndex((m) => m.t === t && pred(m));
        if (i >= 0) return msgs.splice(i, 1)[0];
        await sleep(25);
      }
      throw new Error("timeout waiting for " + t);
    },
  };
}

function audioMsg(n = 3) {
  const parts = [1];
  for (let i = 0; i < n; i++) { parts.push(0, 40); for (let k = 0; k < 40; k++) parts.push(i); }
  return Buffer.from(parts);
}

console.log("console");
let r = await api("GET", "/console/api/state");
ok(r.needsSetup === true && r.signedIn === false, "fresh: needs setup");
r = await api("POST", "/console/api/setup", { password: "short" });
ok(r.status === 400, "short password refused");
r = await api("POST", "/console/api/setup", { password: "correct horse battery" });
ok(r.ok && cookie.startsWith("hollo_console="), "password set, signed in");
r = await api("POST", "/console/api/setup", { password: "another password" });
ok(r.status === 409, "second setup refused");
const saved = cookie; cookie = "";
r = await api("GET", "/console/api/groups");
ok(r.status === 401, "signed-out request refused");
r = await api("POST", "/console/api/login", { password: "wrong password" });
ok(r.status === 401, "wrong password refused");
r = await api("POST", "/console/api/login", { password: "correct horse battery" });
ok(r.ok, "login");
r = await fetch(BASE + "/console/api/groups", { method: "POST", headers: { cookie, "content-type": "application/json" }, body: '{"name":"x"}' });
ok(r.status === 403, "request without console header refused (CSRF)");
cookie = saved;

r = await api("POST", "/console/api/groups", { name: "Family" });
ok(r.group?.id && r.adminInvite?.code, "group created with admin invite");
const gid = r.group.id, base = r.group.base, adminCode = r.adminInvite.code;
ok(r.adminInvite.url === `${base}/join?c=${adminCode}`, "invite url uses group address");
r = await api("GET", "/console/api/groups");
ok(r.groups.length === 1 && r.groups[0].name === "Family" && r.groups[0].members === 0, "group listed");

r = await api("GET", `/g/${gid}/join?c=${adminCode}`, undefined, { raw: true });
const html = await r.text();
ok(r.status === 200 && html.includes("Open in Hollo") && html.includes(encodeURIComponent(base)), "invite page");
r = await api("GET", `/g/zzzzzzzz/v1/me`);
ok(r.status === 404 && r.error === "no_group", "unknown group 404");

console.log("joining");
r = await api("POST", `/g/${gid}/v1/join`, { code: adminCode, name: "Debayan", phone: "+1 206 555 0100", platform: "ios" });
ok(r.token && r.user.role === "admin" && r.user.status === "active", "admin joined");
const adminTok = r.token, adminId = r.user.id;
r = await api("POST", `/g/${gid}/v1/join`, { code: adminCode, name: "Again", platform: "ios" });
ok(r.status === 404 && r.error === "invite_used", "used invite says used");
r = await api("POST", `/g/${gid}/v1/join`, { code: "ABCDEFGHJK", name: "X", platform: "ios" });
ok(r.error === "invalid_invite", "wrong code says wrong");

r = await api("GET", `/g/${gid}/v1/me`, undefined, { token: adminTok });
ok(r.user?.name === "Debayan" && r.settings?.clipTtlHours === 24, "me");
r = await api("GET", `/g/${gid}/v1/channels`, undefined, { token: adminTok });
const fam = r.channels[0];
ok(r.channels.length === 1 && fam.isDefault && fam.name === "Family" && fam.members.length === 1, "default channel with admin");

r = await api("POST", `/g/${gid}/v1/invites`, { channelId: fam.id, label: "Vishnu" }, { token: adminTok });
ok(r.code && r.url.startsWith(base), "app creates invite");
r = await api("POST", `/g/${gid}/v1/join`, { code: r.code.toLowerCase(), name: "Vishnu", phone: "+919784656455", platform: "android" });
ok(r.user?.status === "pending", "member pending (code case-insensitive)");
const memTok = r.token, memId = r.user.id;
r = await api("GET", `/g/${gid}/v1/channels`, undefined, { token: memTok });
ok(r.status === 403 && r.error === "pending", "pending can't list channels");
r = await api("GET", `/console/api/groups/${gid}`);
ok(r.group.users.some((u) => u.id === memId && u.status === "pending"), "console sees pending");
r = await api("POST", `/console/api/groups/${gid}/users/${memId}/approve`, {});
ok(r.user?.status === "active", "console approves");
r = await api("GET", `/g/${gid}/v1/channels`, undefined, { token: memTok });
ok(r.channels[0].members.length === 2, "member in Family");

console.log("talking");
const A = client(base, adminTok), M = client(base, memTok);
await A.start(); await M.start();
await A.wait("presence", (m) => m.userId === memId && m.online && m.duty);
ok(true, "presence: member online on duty");
A.send({ t: "floor_request", channel: fam.id, mode: "hold" });
const g1 = await A.wait("floor_granted");
ok(g1.txId > 0 && g1.maxMs === 120000, "floor granted");
const tk = await M.wait("floor_taken");
ok(tk.userId === adminId && tk.name === "Debayan", "listener told who talks");
M.send({ t: "floor_request", channel: fam.id, mode: "hold" });
const d = await M.wait("floor_denied");
ok(d.reason === "busy", "second talker denied: busy");
for (let i = 0; i < 5; i++) { A.ws.send(audioMsg()); await sleep(20); }
await sleep(300);
ok(M.bins.length === 5 && M.bins[0][0] === 1 && new DataView(M.bins[0].buffer).getUint32(1) === g1.txId, "audio relayed with txId");
A.send({ t: "floor_release" });
const e1 = await M.wait("floor_ended");
ok(e1.reason === "released" && e1.durationMs === 300 && e1.clipId === String(g1.txId), "floor ended, clip saved");
r = await api("GET", `/g/${gid}/v1/channels/${fam.id}/clips`, undefined, { token: memTok });
ok(r.clips.length === 1 && r.clips[0].userName === "Debayan" && r.clips[0].expiresAt, "clip listed");
r = await api("GET", `/g/${gid}/v1/clips/${e1.clipId}/audio`, undefined, { token: memTok, raw: true });
const buf = new Uint8Array(await r.arrayBuffer());
ok(String.fromCharCode(...buf.slice(0, 4)) === "WKC1" && buf.length === 10 + 5 * 3 * 42, "clip file WKC1 with all packets");

console.log("late joiner catch-up");
A.send({ t: "floor_request", channel: fam.id, mode: "handsfree" });
const g2 = await A.wait("floor_granted");
A.ws.send(audioMsg(2));
await sleep(200);
const M2 = client(base, memTok); await M2.start();
await M2.wait("floor_taken", (m) => m.txId === g2.txId);
await sleep(200);
ok(M2.bins.length === 1, "second connection gets buffered audio");
M2.ws.close();

console.log("idle timeout (10 s)");
const e2 = await M.wait("floor_ended", (m) => m.txId === g2.txId, 20000);
ok(e2.reason === "idle", "silent transmission ends: idle");

console.log("personal channel");
r = await api("POST", `/g/${gid}/v1/direct`, { userId: memId }, { token: adminTok });
const dm = r.channel;
ok(dm.kind === "direct" && dm.members.length === 2, "personal channel");
r = await api("GET", `/console/api/groups/${gid}`);
ok(!r.group.channels.some((c) => c.id === dm.id), "console doesn't see personal channels");

console.log("console management");
r = await api("POST", `/console/api/groups/${gid}/channels`, { name: "Cousins", color: "teal", memberIds: [memId] });
ok(r.channel?.members.length === 2, "console creates channel (admin + member)");
await M.wait("channels_changed");
ok(true, "apps told channels changed");
r = await api("POST", `/console/api/groups/${gid}/invites`, { channelId: r.channel.id, label: "Ma" });
ok(r.code && r.channelName === "Cousins", "console invite into channel");
const invId = r.invite.id;
r = await api("DELETE", `/console/api/groups/${gid}/invites/${invId}`);
ok(r.ok, "invite deleted");
r = await api("PATCH", `/console/api/groups/${gid}`, { name: "Datta Family", clipTtlHours: 48 });
ok(r.group.name === "Datta Family" && r.group.settings.clipTtlHours === 48 && r.group.channels[0].name === "Datta Family", "rename + history setting");
r = await api("PATCH", `/console/api/groups/${gid}`, { clipTtlHours: 500 });
ok(r.status === 400 && r.message.includes("168"), "bad history setting explained");

console.log("SOS");
r = await api("POST", `/g/${gid}/v1/sos`, { lat: 23.83, lng: 91.28, accuracy: 10 }, { token: memTok });
const sos = r.sos;
ok(sos.state === "active" && sos.recipients.includes(adminId) && sos.userName === "Vishnu", "SOS created");
const ev = await A.wait("sos");
ok(ev.sos.id === sos.id, "admin receives SOS");
M.send({ t: "floor_request", channel: fam.id, mode: "sos", sosId: sos.id });
await M.wait("floor_granted");
M.ws.send(audioMsg(1));
M.send({ t: "floor_release" });
const ev2 = await A.wait("sos", (m) => m.sos.clipIds.length === 1);
ok(true, "SOS clip attached");
r = await api("POST", `/g/${gid}/v1/sos/${sos.id}/respond`, {}, { token: adminTok });
ok(r.sos.responders.includes(adminId), "admin responding");
r = await api("POST", `/g/${gid}/v1/sos/${sos.id}/resolve`, { resolution: "safe" }, { token: memTok });
ok(r.sos.state === "resolved", "sender closes SOS");

console.log("removal");
r = await api("DELETE", `/console/api/groups/${gid}/users/${memId}`);
ok(r.ok, "console removes member");
await sleep(300);
r = await api("GET", `/g/${gid}/v1/me`, undefined, { token: memTok });
ok(r.status === 401, "removed phone signed out");
r = await api("GET", `/g/${gid}/v1/channels`, undefined, { token: adminTok });
ok(!r.channels.some((c) => c.kind === "direct"), "their personal channel deleted");

A.ws.close(); M.ws.close();
r = await api("DELETE", `/console/api/groups/${gid}`);
ok(r.ok, "group deleted");
r = await api("GET", `/g/${gid}/v1/me`, undefined, { token: adminTok });
ok(r.status === 404, "deleted group gone");

console.log(fails ? `\n${fails} FAILED` : "\nall passed");
process.exit(fails ? 1 : 0);
