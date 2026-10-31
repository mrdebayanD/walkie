// Public pages: the invite page family members open on their phones.
// Same visual language as the console: radio-handset greys, safety orange,
// and the invite code on an LCD strip.

import { groupStub, registry } from "./index";
import { escapeHtml, normalizeCode, type Env } from "./util";

const FONTS = `<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Barlow:wght@400;500;600&family=Barlow+Condensed:wght@600;700&family=Share+Tech+Mono&display=swap" rel="stylesheet">`;

const BASE_CSS = `
:root{--body:#E4E7EA;--panel:#F5F6F7;--ink:#17202C;--muted:#4F5A68;--line:#C3CAD2;--signal:#F26B1D;--on-signal:#1A0D02;--lcd:#CBDABB;--lcd-ink:#1D2913;--focus:#1F5FD1;color-scheme:light dark}
@media (prefers-color-scheme:dark){:root{--body:#14191F;--panel:#1D242C;--ink:#E8ECF0;--muted:#A3AEBA;--line:#33404C;--signal:#FF7B30;--on-signal:#1A0D02;--lcd:#1C2816;--lcd-ink:#BDE38B;--focus:#7FB0FF}}
*{box-sizing:border-box}
html{-webkit-text-size-adjust:100%}
body{margin:0;background:var(--body);color:var(--ink);font:400 17px/1.5 Barlow,system-ui,sans-serif}
a{color:inherit}
:focus-visible{outline:3px solid var(--focus);outline-offset:2px}
h1{font:700 clamp(40px,11vw,64px)/.95 "Barlow Condensed",Barlow,sans-serif;letter-spacing:-.01em;margin:0}
.wrap{max-width:460px;margin:0 auto;padding:32px 16px 48px}
.handset{background:var(--panel);border:1px solid var(--line);border-radius:28px;padding:28px 22px;box-shadow:0 1px 0 rgba(255,255,255,.5) inset}
.lead{color:var(--muted);margin:12px 0 24px}
.lcd{background:var(--lcd);color:var(--lcd-ink);border-radius:12px;padding:14px 16px;font:400 clamp(30px,9vw,40px)/1 "Share Tech Mono",ui-monospace,monospace;letter-spacing:.12em;text-align:center;box-shadow:inset 0 2px 6px rgba(0,0,0,.18)}
.btn{width:100%;cursor:pointer;display:flex;align-items:center;justify-content:center;gap:10px;min-height:56px;border-radius:16px;font:600 18px/1.2 Barlow,sans-serif;text-decoration:none;border:1px solid var(--line);background:var(--body);color:var(--ink);margin:10px 0;padding:12px 16px}
.btn.go{background:var(--signal);color:var(--on-signal);border-color:transparent}
ol{list-style:none;padding:0;margin:0;counter-reset:s}
ol>li{counter-increment:s;position:relative;padding-left:44px;margin:0 0 22px}
ol>li::before{content:counter(s);position:absolute;left:0;top:-1px;width:30px;height:30px;border-radius:50%;background:var(--ink);color:var(--panel);font:700 17px/30px "Barlow Condensed",sans-serif;text-align:center}
.step{font-weight:600;margin:0 0 6px}
.small{font-size:15px;color:var(--muted);margin:6px 0 0}
.foot{color:var(--muted);font-size:14px;margin-top:20px;text-align:center}
`;

function page(title: string, body: string, status = 200): Response {
  return new Response(
    `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>${escapeHtml(title)}</title><meta name="robots" content="noindex">${FONTS}<style>${BASE_CSS}</style></head><body>${body}</body></html>`,
    { status, headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store", "x-content-type-options": "nosniff", "referrer-policy": "no-referrer" } },
  );
}

export function notFoundPage(): Response {
  return page("Not found", `<main class="wrap"><div class="handset"><h1>No signal</h1><p class="lead">There's nothing at this address. Check the link you were sent.</p></div></main>`, 404);
}

export async function joinPage(env: Env, gid: string, base: string, rawCode: string): Promise<Response> {
  const code = normalizeCode(rawCode);
  const summary = await groupStub(env, gid).summary();
  if (!summary) return page("Invite not found", `<main class="wrap"><div class="handset"><h1>No signal</h1><p class="lead">This group doesn't exist any more. Ask for a new invite.</p></div></main>`, 404);
  if (!/^[0-9A-Z]{10}$/.test(code)) return page("Invite link is incomplete", `<main class="wrap"><div class="handset"><h1>Link cut short</h1><p class="lead">This invite link is missing its code. Ask for the link again and open the whole thing.</p></div></main>`, 400);

  const s = await registry(env).settings();
  const app = `walkie://join?s=${encodeURIComponent(base)}&c=${code}`;
  const name = escapeHtml(summary.name);
  const install: string[] = [];
  if (s.apk) install.push(`<a class="btn" href="/install/hollo.apk">Android: download Hollo</a><p class="small">Open the downloaded file. If Android asks, allow installing apps from this source once.</p>`);
  if (s.testflightUrl) install.push(`<a class="btn" href="${escapeHtml(s.testflightUrl)}">iPhone: get Hollo on TestFlight</a><p class="small">Install the free TestFlight app from the App Store first if it asks.</p>`);
  if (!install.length) install.push(`<p class="small">Ask the person who invited you for the iPhone or Android install link.</p>`);

  return page(
    `Join ${summary.name} on Hollo`,
    `<main class="wrap"><div class="handset">
<h1>${name}</h1>
<p class="lead">You're invited to talk with ${name} on Hollo, a private walkie-talkie.</p>
<ol>
<li><p class="step">Install Hollo</p>${install.join("")}<p class="small">Already have it? Skip to the next step.</p></li>
<li><p class="step">Open your invite</p><a class="btn go" href="${app}">Open in Hollo</a></li>
</ol>
<p class="step">Button didn't open the app?</p>
<p class="small">Copy this invite and paste it into Hollo's invite box.</p>
<button class="btn" type="button" id="copy" data-link="${escapeHtml(base)}/join?c=${code}">Copy invite link</button>
<div class="lcd" aria-label="Invite code ${code.split("").join(" ")}">${code}</div>
<p class="small">Works once, for 24 hours.</p>
</div><p class="foot">Hollo</p></main>
<script>document.getElementById("copy").onclick=async e=>{const b=e.currentTarget;try{await navigator.clipboard.writeText(b.dataset.link);b.textContent="Copied"}catch{prompt("Copy this link",b.dataset.link)}setTimeout(()=>b.textContent="Copy invite link",2500)}</script>`,
  );
}
