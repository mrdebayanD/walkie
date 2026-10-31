// Small helpers shared by the Worker and the Durable Objects.

export interface Env {
  GROUP: DurableObjectNamespace<import("./group").Group>;
  REGISTRY: DurableObjectNamespace<import("./registry").Registry>;
  ASSETS: Fetcher;
  APNS_BUNDLE_ID: string;
  // Optional push secrets (wrangler secret put ...)
  APNS_KEY_P8?: string;
  APNS_KEY_ID?: string;
  APNS_TEAM_ID?: string;
  APNS_CRITICAL?: string;
  FCM_SERVICE_ACCOUNT?: string;
}

/** An error with an HTTP status. Its message is "status|code|text" so it
 * survives being thrown across a Durable Object RPC call (which keeps only
 * the message); use toHttpError() on the receiving side. */
export class HttpError extends Error {
  constructor(public status: number, public code: string, public text: string) {
    super(`${status}|${code}|${text}`);
  }
}

export function toHttpError(e: unknown): HttpError | null {
  if (e instanceof HttpError) return e;
  const m = e instanceof Error ? e.message.match(/^(\d{3})\|([\w-]+)\|([\s\S]*)$/) : null;
  return m ? new HttpError(Number(m[1]), m[2], m[3]) : null;
}

export function json(v: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(v), {
    status,
    headers: { "content-type": "application/json", "cache-control": "no-store", "x-content-type-options": "nosniff", ...headers },
  });
}

export function errJson(status: number, code: string, message: string): Response {
  return json({ error: code, message }, status);
}

export async function readJSON<T = any>(req: Request): Promise<T> {
  const text = await req.text();
  if (text.length > 64 * 1024) throw new HttpError(413, "too_large", "Request too large");
  if (!text) return {} as T;
  try {
    const v = JSON.parse(text);
    if (typeof v !== "object" || v === null || Array.isArray(v)) throw new Error();
    return v as T;
  } catch {
    throw new HttpError(400, "bad_request", "Invalid request");
  }
}

const enc = new TextEncoder();

function randomBytes(n: number): Uint8Array {
  const b = new Uint8Array(n);
  crypto.getRandomValues(b);
  return b;
}

export function b64url(b: Uint8Array | ArrayBuffer): string {
  const u = b instanceof Uint8Array ? b : new Uint8Array(b);
  let s = "";
  for (const x of u) s += String.fromCharCode(x);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function fromB64(s: string): Uint8Array {
  const t = s.replace(/-/g, "+").replace(/_/g, "/");
  const bin = atob(t + "=".repeat((4 - (t.length % 4)) % 4));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

export const newID = () => b64url(randomBytes(9));
export const newToken = () => b64url(randomBytes(32));

/** Short lowercase id for group URLs (/g/<id>). */
export function newGroupID(): string {
  const a = "abcdefghjkmnpqrstuvwxyz23456789";
  let s = "";
  for (const x of randomBytes(10)) s += a[x % a.length];
  return s;
}

// Crockford-style alphabet without I, L, O, U to avoid misreading.
const CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

export function newInviteCode(): string {
  const b = randomBytes(7);
  let bits = 0, val = 0, out = "";
  for (const x of b) {
    val = (val << 8) | x;
    bits += 8;
    while (bits >= 5) {
      out += CODE_ALPHABET[(val >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  return out.slice(0, 10);
}

export function normalizeCode(c: string): string {
  return (c || "").trim().toUpperCase().replace(/[-\s]/g, "").replace(/O/g, "0").replace(/[IL]/g, "1");
}

export async function sha256hex(s: string): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", enc.encode(s));
  return [...new Uint8Array(d)].map((x) => x.toString(16).padStart(2, "0")).join("");
}

export function cleanName(n: unknown, max: number): string {
  if (typeof n !== "string") throw new HttpError(400, "bad_name", "Name is required");
  const t = n.trim();
  if (!t) throw new HttpError(400, "bad_name", "Name is required");
  if ([...t].length > max) throw new HttpError(400, "bad_name", `Name must be at most ${max} characters`);
  if (/[\u0000-\u001f]/.test(t)) throw new HttpError(400, "bad_name", "Name has invalid characters");
  return t;
}

export function cleanPhone(p: unknown): string {
  if (p === undefined || p === null) return "";
  if (typeof p !== "string") throw new HttpError(400, "bad_phone", "Phone number has invalid characters");
  let out = "";
  [...p.trim()].forEach((ch, i) => {
    if (ch >= "0" && ch <= "9") out += ch;
    else if (ch === "+" && i === 0) out += ch;
    else if (" -()".includes(ch)) {
      /* skip */
    } else throw new HttpError(400, "bad_phone", "Phone number has invalid characters");
  });
  if (out && (out.length < 6 || out.length > 16)) throw new HttpError(400, "bad_phone", "Phone number length looks wrong");
  return out;
}

export const COLORS = ["green", "blue", "orange", "pink", "purple", "red", "teal", "yellow"];

export const contains = <T>(xs: T[], x: T) => xs.includes(x);
export const addUnique = <T>(xs: T[], x: T) => (xs.includes(x) ? xs : [...xs, x]);
export const remove = <T>(xs: T[], x: T) => xs.filter((v) => v !== x);

// ---- password hashing and signed cookies for the console ----

export async function pbkdf2(password: string, salt: Uint8Array, iterations: number): Promise<string> {
  const key = await crypto.subtle.importKey("raw", enc.encode(password), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", hash: "SHA-256", salt, iterations }, key, 256);
  return b64url(bits);
}

export const newSalt = () => randomBytes(16);
export const newKey = () => b64url(randomBytes(32));

export async function hmac(keyB64: string, data: string): Promise<string> {
  const key = await crypto.subtle.importKey("raw", fromB64(keyB64), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return b64url(await crypto.subtle.sign("HMAC", key, enc.encode(data)));
}

export function safeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let r = 0;
  for (let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return r === 0;
}

export function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);
}
