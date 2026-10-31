// The single Registry object: list of groups, console password, install links,
// and the Android APK (stored in chunks; one stored value is capped at 2 MB).

import { DurableObject } from "cloudflare:workers";
import type { Env } from "./util";

export interface GroupEntry { id: string; name: string; createdAt: string }
export interface Auth { salt: string; hash: string; iter: number; sessionKey: string }
export interface Settings { testflightUrl: string; apk?: { size: number; chunks: number; updatedAt: string; name: string } }

const CHUNK = 1_000_000;

export class Registry extends DurableObject<Env> {
  private loginHits = new Map<string, number[]>();

  async groups(): Promise<GroupEntry[]> {
    return (await this.ctx.storage.get<GroupEntry[]>("groups")) ?? [];
  }

  async addGroup(g: GroupEntry) {
    const gs = await this.groups();
    if (gs.length >= 50) throw new Error("limit");
    gs.push(g);
    await this.ctx.storage.put("groups", gs);
  }

  async renameGroup(id: string, name: string) {
    const gs = await this.groups();
    const g = gs.find((x) => x.id === id);
    if (g) g.name = name;
    await this.ctx.storage.put("groups", gs);
  }

  async removeGroup(id: string) {
    await this.ctx.storage.put("groups", (await this.groups()).filter((g) => g.id !== id));
  }

  async hasGroup(id: string) {
    return (await this.groups()).some((g) => g.id === id);
  }

  async auth(): Promise<Auth | null> {
    return (await this.ctx.storage.get<Auth>("auth")) ?? null;
  }

  /** Only succeeds the first time (no password set yet). */
  async setAuthIfUnset(a: Auth): Promise<boolean> {
    if (await this.ctx.storage.get("auth")) return false;
    await this.ctx.storage.put("auth", a);
    return true;
  }

  async replaceAuth(a: Auth) {
    await this.ctx.storage.put("auth", a);
  }

  /** Simple per-IP limiter for password attempts: 10 per 15 minutes. */
  async allowLogin(ip: string): Promise<boolean> {
    const now = Date.now();
    const hits = (this.loginHits.get(ip) ?? []).filter((t) => now - t < 15 * 60_000);
    if (hits.length >= 10) return false;
    hits.push(now);
    this.loginHits.set(ip, hits);
    return true;
  }

  async settings(): Promise<Settings> {
    return (await this.ctx.storage.get<Settings>("settings")) ?? { testflightUrl: "" };
  }

  async setTestflight(url: string) {
    const s = await this.settings();
    s.testflightUrl = url;
    await this.ctx.storage.put("settings", s);
  }

  async putApk(data: ArrayBuffer, name: string) {
    const s = await this.settings();
    const old = s.apk?.chunks ?? 0;
    const n = Math.ceil(data.byteLength / CHUNK);
    for (let i = 0; i < n; i++) await this.ctx.storage.put("apk:" + i, data.slice(i * CHUNK, (i + 1) * CHUNK));
    for (let i = n; i < old; i++) await this.ctx.storage.delete("apk:" + i);
    s.apk = { size: data.byteLength, chunks: n, updatedAt: new Date().toISOString(), name };
    await this.ctx.storage.put("settings", s);
  }

  async deleteApk() {
    const s = await this.settings();
    for (let i = 0; i < (s.apk?.chunks ?? 0); i++) await this.ctx.storage.delete("apk:" + i);
    delete s.apk;
    await this.ctx.storage.put("settings", s);
  }

  async apkChunk(i: number): Promise<ArrayBuffer | undefined> {
    return this.ctx.storage.get<ArrayBuffer>("apk:" + i);
  }
}
