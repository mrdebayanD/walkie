// Admin console API. One password (set on first visit), a signed session
// cookie, and a custom header on every call (blocks cross-site requests).

import { groupStub, registry } from "./index";
import {
  HttpError, toHttpError, cleanName, errJson, hmac, json, newGroupID, newKey, newSalt, pbkdf2, readJSON, safeEqual, b64url, fromB64,
  type Env,
} from "./util";

const COOKIE = "hollo_console";
const SESSION_DAYS = 30;
const ITER = 100_000; // Workers' PBKDF2 maximum

async function sessionCookie(key: string): Promise<string> {
  const exp = Date.now() + SESSION_DAYS * 86_400_000;
  const v = `${exp}.${await hmac(key, String(exp))}`;
  return `${COOKIE}=${v}; Path=/console/; HttpOnly; Secure; SameSite=Strict; Max-Age=${SESSION_DAYS * 86400}`;
}

async function signedIn(req: Request, env: Env): Promise<boolean> {
  const auth = await registry(env).auth();
  if (!auth) return false;
  const m = (req.headers.get("cookie") ?? "").match(new RegExp(`${COOKIE}=([0-9]+)\\.([A-Za-z0-9_-]+)`));
  if (!m || Number(m[1]) < Date.now()) return false;
  return safeEqual(m[2], await hmac(auth.sessionKey, m[1]));
}

function checkPassword(p: unknown): string {
  if (typeof p !== "string" || p.length < 8) throw new HttpError(400, "weak_password", "Use at least 8 characters");
  if (p.length > 200) throw new HttpError(400, "weak_password", "That password is too long");
  return p;
}

export async function consoleApi(req: Request, env: Env, url: URL): Promise<Response> {
  try {
    return await handle(req, env, url);
  } catch (e) {
    const h = toHttpError(e);
    if (h) return errJson(h.status, h.code, h.text);
    console.error("console error", e);
    return errJson(500, "internal", "Something went wrong. Try again.");
  }
}

async function handle(req: Request, env: Env, url: URL): Promise<Response> {
  const path = url.pathname.slice("/console/api".length);
  const m = req.method;
  const reg = registry(env);
  const ip = req.headers.get("cf-connecting-ip") ?? "?";

  if (m !== "GET" && req.headers.get("x-hollo") !== "1") throw new HttpError(403, "forbidden", "Missing console header");

  if (m === "GET" && path === "/state") {
    const auth = await reg.auth();
    return json({ needsSetup: !auth, signedIn: await signedIn(req, env) });
  }

  if (m === "POST" && path === "/setup") {
    const body = await readJSON(req);
    const pw = checkPassword(body.password);
    const salt = newSalt();
    const auth = { salt: b64url(salt), hash: await pbkdf2(pw, salt, ITER), iter: ITER, sessionKey: newKey() };
    if (!(await reg.setAuthIfUnset(auth))) throw new HttpError(409, "already_set", "A password is already set. Sign in instead.");
    return json({ ok: true }, 200, { "set-cookie": await sessionCookie(auth.sessionKey) });
  }

  if (m === "POST" && path === "/login") {
    if (!(await reg.allowLogin(ip))) throw new HttpError(429, "rate_limited", "Too many attempts. Wait 15 minutes.");
    const body = await readJSON(req);
    const auth = await reg.auth();
    if (!auth) throw new HttpError(409, "needs_setup", "Set a password first");
    const h = await pbkdf2(String(body.password ?? ""), fromB64(auth.salt), auth.iter);
    if (!safeEqual(h, auth.hash)) throw new HttpError(401, "wrong_password", "That password isn't right");
    return json({ ok: true }, 200, { "set-cookie": await sessionCookie(auth.sessionKey) });
  }

  if (m === "POST" && path === "/logout") {
    return json({ ok: true }, 200, { "set-cookie": `${COOKIE}=; Path=/console/; HttpOnly; Secure; SameSite=Strict; Max-Age=0` });
  }

  if (!(await signedIn(req, env))) throw new HttpError(401, "signed_out", "Sign in again");

  // ---- signed in ----

  if (m === "POST" && path === "/password") {
    const body = await readJSON(req);
    const auth = (await reg.auth())!;
    if (!safeEqual(await pbkdf2(String(body.current ?? ""), fromB64(auth.salt), auth.iter), auth.hash)) {
      throw new HttpError(401, "wrong_password", "Your current password isn't right");
    }
    const pw = checkPassword(body.password);
    const salt = newSalt();
    // A new session key signs everyone else out.
    const next = { salt: b64url(salt), hash: await pbkdf2(pw, salt, ITER), iter: ITER, sessionKey: newKey() };
    await reg.replaceAuth(next);
    return json({ ok: true }, 200, { "set-cookie": await sessionCookie(next.sessionKey) });
  }

  if (path === "/settings") {
    if (m === "GET") {
      const s = await reg.settings();
      return json({ testflightUrl: s.testflightUrl, apk: s.apk ?? null, apkUrl: s.apk ? `${url.origin}/install/hollo.apk` : null });
    }
    if (m === "PUT") {
      const body = await readJSON(req);
      const t = String(body.testflightUrl ?? "").trim();
      if (t && !/^https:\/\/testflight\.apple\.com\/join\/[A-Za-z0-9]+$/.test(t)) {
        throw new HttpError(400, "bad_url", "Paste the public TestFlight link (https://testflight.apple.com/join/...)");
      }
      await reg.setTestflight(t);
      return json({ ok: true });
    }
  }

  if (path === "/apk") {
    if (m === "PUT") {
      const data = await req.arrayBuffer();
      if (data.byteLength < 1000 || data.byteLength > 40_000_000) throw new HttpError(400, "bad_apk", "That doesn't look like an Android app file");
      const head = new Uint8Array(data, 0, 2);
      if (head[0] !== 0x50 || head[1] !== 0x4b) throw new HttpError(400, "bad_apk", "That doesn't look like an Android app (.apk) file");
      await reg.putApk(data, req.headers.get("x-file-name") ?? "hollo.apk");
      return json({ ok: true });
    }
    if (m === "DELETE") {
      await reg.deleteApk();
      return json({ ok: true });
    }
  }

  if (path === "/groups") {
    if (m === "GET") {
      const gs = await reg.groups();
      const out = await Promise.all(gs.map(async (g) => ({ ...g, base: `${url.origin}/g/${g.id}`, ...((await groupStub(env, g.id).summary()) ?? {}) })));
      return json({ groups: out });
    }
    if (m === "POST") {
      const body = await readJSON(req);
      const name = cleanName(body.name, 30);
      const id = newGroupID();
      const base = `${url.origin}/g/${id}`;
      const invite = await groupStub(env, id).init(name, base);
      await reg.addGroup({ id, name, createdAt: new Date().toISOString() });
      return json({ group: { id, name, base }, adminInvite: invite });
    }
  }

  const gm = path.match(/^\/groups\/([a-z0-9]+)(?:\/(\w+)(?:\/([\w-]+)(?:\/(\w+))?)?)?$/);
  if (gm) {
    const [, gid, sub, id, action] = gm;
    if (!(await reg.hasGroup(gid))) throw new HttpError(404, "no_group", "That group doesn't exist");
    const g = groupStub(env, gid);
    const base = `${url.origin}/g/${gid}`;
    const body = m === "GET" || m === "DELETE" ? {} : await readJSON(req);

    if (!sub) {
      if (m === "GET") return json({ group: { id: gid, base, ...(await g.adminView()) } });
      if (m === "PATCH") {
        if (body.name !== undefined) {
          await g.rename(body.name);
          await reg.renameGroup(gid, cleanName(body.name, 30));
        }
        if (body.clipTtlHours !== undefined) await g.setTtl(Number(body.clipTtlHours));
        return json({ group: { id: gid, base, ...(await g.adminView()) } });
      }
      if (m === "DELETE") {
        await g.destroy();
        await reg.removeGroup(gid);
        return json({ ok: true });
      }
    }
    if (sub === "channels") {
      if (m === "POST" && !id) return json({ channel: await g.consoleCreateChannel(body.name, body.color, body.memberIds ?? []) });
      if (m === "PATCH" && id) return json({ channel: await g.consoleUpdateChannel(id, body.name, body.color, body.memberIds) });
      if (m === "DELETE" && id) { await g.consoleDeleteChannel(id); return json({ ok: true }); }
    }
    if (sub === "invites") {
      if (m === "POST" && !id) return json(await g.createInvite(String(body.channelId ?? ""), String(body.label ?? ""), body.admin === true, base));
      if (m === "DELETE" && id) { await g.deleteInvite(id); return json({ ok: true }); }
    }
    if (sub === "users" && id) {
      if (m === "POST" && action === "approve") return json({ user: await g.approve(id) });
      if (m === "PATCH" && !action) return json({ user: await g.consoleUpdateUser(id, body) });
      if (m === "DELETE" && !action) { await g.removeUser(id); return json({ ok: true }); }
    }
  }

  return errJson(404, "not_found", "Not found");
}
