// iPhone (APNs) and Android (FCM v1) push, using WebCrypto for the JWTs.
// Missing secrets simply turn that platform's push off.

import { b64url, fromB64, type Env } from "./util";

export type PushKind = "tx" | "sos" | "sos_resolved";

export interface PushMessage {
  kind: PushKind;
  channel?: string;
  txId?: number;
  speaker: string; // talker or SOS sender
  sosId?: string;
  resolution?: string;
  repeat?: boolean;
}

export class TokenGone extends Error {}

const enc = new TextEncoder();

function pemBody(pem: string): Uint8Array {
  const body = pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  return fromB64(body.replace(/\+/g, "-").replace(/\//g, "_"));
}

async function signJWT(header: object, claims: object, key: CryptoKey, alg: { name: string; hash?: string }): Promise<string> {
  const unsigned = b64url(enc.encode(JSON.stringify(header))) + "." + b64url(enc.encode(JSON.stringify(claims)));
  // WebCrypto ECDSA returns r||s (the JOSE format), RSA returns PKCS#1 v1.5.
  const sig = await crypto.subtle.sign(alg, key, enc.encode(unsigned));
  return unsigned + "." + b64url(sig);
}

// Cached per isolate.
let apnsCache: { jwt: string; at: number } | null = null;
let fcmCache: { token: string; exp: number } | null = null;

export function pushEnabled(env: Env) {
  return { ios: !!(env.APNS_KEY_P8 && env.APNS_KEY_ID && env.APNS_TEAM_ID), android: !!env.FCM_SERVICE_ACCOUNT };
}

async function apnsJWT(env: Env): Promise<string> {
  if (apnsCache && Date.now() - apnsCache.at < 40 * 60_000) return apnsCache.jwt;
  const key = await crypto.subtle.importKey("pkcs8", pemBody(env.APNS_KEY_P8!), { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
  const jwt = await signJWT({ alg: "ES256", kid: env.APNS_KEY_ID }, { iss: env.APNS_TEAM_ID, iat: Math.floor(Date.now() / 1000) }, key, { name: "ECDSA", hash: "SHA-256" });
  apnsCache = { jwt, at: Date.now() };
  return jwt;
}

async function apnsSend(env: Env, token: string, sandbox: boolean, pushType: string, topic: string, expiration: number, payload: object) {
  const host = sandbox ? "https://api.sandbox.push.apple.com" : "https://api.push.apple.com";
  const resp = await fetch(`${host}/3/device/${encodeURIComponent(token)}`, {
    method: "POST",
    headers: {
      authorization: "bearer " + (await apnsJWT(env)),
      "apns-push-type": pushType,
      "apns-topic": topic,
      "apns-priority": "10",
      "apns-expiration": String(expiration),
    },
    body: JSON.stringify(payload),
  });
  if (resp.status === 200) return;
  const body = (await resp.text()).slice(0, 2000);
  if (resp.status === 410 || /BadDeviceToken|Unregistered/.test(body)) throw new TokenGone();
  if (resp.status === 403 && body.includes("ExpiredProviderToken")) apnsCache = null;
  throw new Error(`apns ${resp.status}: ${body}`);
}

export async function pushAPNsPTT(env: Env, token: string, sandbox: boolean, m: PushMessage) {
  const payload = { aps: {}, type: "tx", channel: m.channel, txId: m.txId, speaker: m.speaker };
  // PTT pushes must be delivered now or never.
  await apnsSend(env, token, sandbox, "pushtotalk", env.APNS_BUNDLE_ID + ".voip-ptt", 0, payload);
}

export async function pushAPNsAlert(env: Env, token: string, sandbox: boolean, m: PushMessage) {
  let aps: Record<string, unknown>;
  if (m.kind === "sos") {
    const critical = env.APNS_CRITICAL === "1";
    aps = {
      alert: { title: "SOS from " + m.speaker, body: "Tap to see where they are and call them." },
      sound: critical ? { critical: 1, name: "default", volume: 1.0 } : "default",
      "interruption-level": critical ? "critical" : "time-sensitive",
      "thread-id": "sos-" + m.sosId,
      category: "SOS",
    };
  } else if (m.kind === "sos_resolved") {
    const body = m.resolution === "false_alarm" ? `${m.speaker}'s SOS was a false alarm.` : `${m.speaker} is safe.`;
    aps = { alert: { title: "SOS closed", body }, sound: "default", "interruption-level": "time-sensitive", "thread-id": "sos-" + m.sosId };
  } else return;
  await apnsSend(env, token, sandbox, "alert", env.APNS_BUNDLE_ID, Math.floor(Date.now() / 1000) + 600, { aps, type: m.kind, sosId: m.sosId });
}

interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
  token_uri?: string;
}

async function fcmAccessToken(sa: ServiceAccount): Promise<string> {
  if (fcmCache && fcmCache.exp - Date.now() > 5 * 60_000) return fcmCache.token;
  const key = await crypto.subtle.importKey("pkcs8", pemBody(sa.private_key), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const now = Math.floor(Date.now() / 1000);
  const aud = sa.token_uri || "https://oauth2.googleapis.com/token";
  const assertion = await signJWT(
    { alg: "RS256", typ: "JWT" },
    { iss: sa.client_email, scope: "https://www.googleapis.com/auth/firebase.messaging", aud, iat: now, exp: now + 3600 },
    key,
    { name: "RSASSA-PKCS1-v1_5" },
  );
  const resp = await fetch(aud, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }),
  });
  const out = (await resp.json().catch(() => ({}))) as { access_token?: string; expires_in?: number };
  if (!out.access_token) throw new Error(`google token exchange failed (${resp.status})`);
  fcmCache = { token: out.access_token, exp: Date.now() + (out.expires_in ?? 3600) * 1000 };
  return out.access_token;
}

export async function pushFCM(env: Env, token: string, m: PushMessage) {
  const sa = JSON.parse(env.FCM_SERVICE_ACCOUNT!) as ServiceAccount;
  const at = await fcmAccessToken(sa);
  const data: Record<string, string> = { type: m.kind };
  let ttl = "30s";
  if (m.kind === "tx") {
    Object.assign(data, { channel: m.channel ?? "", txId: String(m.txId ?? 0), speaker: m.speaker });
    ttl = "20s";
  } else if (m.kind === "sos") {
    Object.assign(data, { sosId: m.sosId ?? "", sender: m.speaker });
    ttl = "600s";
  } else {
    Object.assign(data, { sosId: m.sosId ?? "", sender: m.speaker, resolution: m.resolution ?? "" });
    ttl = "3600s";
  }
  const resp = await fetch(`https://fcm.googleapis.com/v1/projects/${encodeURIComponent(sa.project_id)}/messages:send`, {
    method: "POST",
    headers: { authorization: "Bearer " + at, "content-type": "application/json" },
    body: JSON.stringify({ message: { token, data, android: { priority: "high", ttl } } }),
  });
  if (resp.status === 200) return;
  const body = (await resp.text()).slice(0, 2000);
  if (resp.status === 404 || body.includes("UNREGISTERED")) throw new TokenGone();
  throw new Error(`fcm ${resp.status}: ${body}`);
}
