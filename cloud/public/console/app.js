// Hollo console: groups ("servers"), channels, people, invites.
// Plain JS, no build step. Every server call goes through api().
"use strict";

const $app = document.getElementById("app");
const $sheet = document.getElementById("sheet");
const $toast = document.getElementById("toast");

const S = { session: null, groups: [], group: null, settings: null, wide: matchMedia("(min-width: 960px)") };

const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
const initials = (n) => (n || "?").trim().split(/\s+/).slice(0, 2).map((p) => [...p][0] || "").join("").toUpperCase();
const COLORS = ["green", "blue", "orange", "pink", "purple", "red", "teal", "yellow"];
const plural = (n, one, many) => `${n} ${n === 1 ? one : many}`;

const ICON = {
  copy: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h10"/></svg>',
  share: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M12 3v13M7 8l5-5 5 5"/><path d="M5 13v6a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-6"/></svg>',
  gear: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/></svg>',
  back: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M15 18l-6-6 6-6"/></svg>',
  close: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18"/></svg>',
  plus: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg>',
};

// ------------------------------------------------------------------ server

async function api(method, path, body, opts = {}) {
  const headers = { "x-hollo": "1" };
  let payload;
  if (body instanceof Blob || body instanceof ArrayBuffer) { payload = body; if (opts.fileName) headers["x-file-name"] = opts.fileName; }
  else if (body !== undefined) { headers["content-type"] = "application/json"; payload = JSON.stringify(body); }
  let r;
  try {
    r = await fetch("/console/api" + path, { method, headers, body: payload, credentials: "same-origin" });
  } catch {
    throw new Error("Can't reach Hollo. Check your connection and try again.");
  }
  const data = await r.json().catch(() => ({}));
  if (r.status === 401 && data.error === "signed_out") { S.session = { signedIn: false, needsSetup: false }; render(); }
  if (!r.ok) throw new Error(data.message || "Something went wrong. Try again.");
  return data;
}

function toast(msg) {
  $toast.textContent = msg;
  $toast.classList.add("show");
  clearTimeout(toast.t);
  toast.t = setTimeout(() => $toast.classList.remove("show"), 2600);
}

async function copy(text, what) {
  try { await navigator.clipboard.writeText(text); toast(`${what} copied`); }
  catch {
    const ta = document.createElement("textarea");
    ta.value = text; document.body.appendChild(ta); ta.select();
    try { document.execCommand("copy"); toast(`${what} copied`); } catch { prompt("Copy this:", text); }
    ta.remove();
  }
}

async function share(title, text, url) {
  if (navigator.share) {
    try { await navigator.share({ title, text, url }); } catch { /* cancelled */ }
  } else copy(`${text} ${url}`, "Message");
}

// ------------------------------------------------------------------ routing

function route() {
  const h = location.hash.replace(/^#\/?/, "");
  if (h.startsWith("g/")) return { view: "group", id: h.slice(2) };
  if (h === "settings") return { view: "settings" };
  return { view: "home" };
}
const go = (hash) => { if (location.hash !== hash) location.hash = hash; else render(); };
addEventListener("hashchange", () => load());
S.wide.addEventListener("change", () => render());

async function load() {
  try {
    if (!S.session) S.session = await api("GET", "/state");
    if (!S.session.signedIn) return render();
    const r = route();
    S.groups = (await api("GET", "/groups")).groups;
    if (r.view === "group") S.group = (await api("GET", `/groups/${r.id}`)).group;
    else S.group = null;
    if (r.view === "settings") S.settings = await api("GET", "/settings");
    if (r.view === "home" && S.wide.matches && S.groups.length) return go(`#/g/${S.groups[0].id}`);
    render();
  } catch (e) {
    if (S.session?.signedIn !== false) {
      $app.innerHTML = `<div class="gate"><div class="handset"><h2>No signal</h2><p class="muted">${esc(e.message)}</p><button class="btn go block" data-action="reload">Try again</button></div></div>`;
    }
  }
}

// ------------------------------------------------------------------ views

function render() {
  if (!S.session?.signedIn) return renderGate();
  const r = route();
  const rail = `<nav class="rail" aria-label="Groups">${groupList()}${newGroupButton("block")}</nav>`;
  let main;
  if (r.view === "group" && S.group) main = groupView(S.group);
  else if (r.view === "settings") main = settingsView();
  else main = homeView();
  $app.innerHTML = `
    <div class="shell">
      <header class="top">
        ${r.view !== "home" ? `<a class="icon-btn only-narrow" href="#/" aria-label="All groups">${ICON.back}</a>` : ""}
        <a class="mark" href="#/"><i aria-hidden="true">H</i>Hollo</a>
        <span class="spacer"></span>
        <a class="icon-btn" href="#/settings" aria-label="Console settings">${ICON.gear}</a>
      </header>
      ${rail}
      <main class="main" id="main">${main}</main>
    </div>`;
}

function groupList() {
  if (!S.groups.length) return `<p class="muted small">No groups yet.</p>`;
  const cur = route().id;
  return `<ul class="glist">${S.groups.map((g) => `
    <li><a href="#/g/${esc(g.id)}" ${g.id === cur ? 'aria-current="page"' : ""}>
      <span class="gname">${esc(g.name)}</span>
      <span class="gstats">${plural(g.members ?? 0, "person", "people")}, ${g.online ?? 0} online</span>
      ${g.activeSos ? `<span class="badge">SOS active</span>` : g.pending ? `<span class="badge">${g.pending} waiting</span>` : ""}
    </a></li>`).join("")}</ul>`;
}

function newGroupButton(cls = "") {
  return `<button class="btn go ${cls}" data-action="new-group" style="margin-top:16px">${ICON.plus}New group</button>`;
}

function homeView() {
  if (!S.groups.length) {
    return `<h1 class="display">Start a group</h1>
      <p class="muted" style="max-width:52ch;margin:16px 0 24px">A group is one private walkie-talkie: its own people, channels, history and SOS. Make one for the family, another for friends.</p>
      ${newGroupButton()}`;
  }
  return `<h1 class="display">Groups</h1><div style="margin-top:20px">${groupList()}</div>
    <button class="btn go fab" data-action="new-group">${ICON.plus}New group</button>`;
}

function memberNames(ch) {
  const n = ch.members.map((m) => m.name);
  if (!n.length) return "No one yet";
  if (n.length <= 3) return n.join(", ");
  return `${n.slice(0, 2).join(", ")} and ${n.length - 2} more`;
}

function ago(iso) {
  const ms = Date.parse(iso) - Date.now();
  const h = Math.round(ms / 3_600_000);
  if (h >= 1) return `${h} h left`;
  return `${Math.max(1, Math.round(ms / 60_000))} min left`;
}

function groupView(g) {
  const people = g.users.filter((u) => u.status === "active");
  const waiting = g.users.filter((u) => u.status === "pending");
  const online = people.filter((u) => u.online).length;
  const chName = (id) => g.channels.find((c) => c.id === id)?.name ?? "Main channel";
  return `
  <div class="ghead">
    <h1 class="display">${esc(g.name)}</h1>
    <div class="stats">
      <span><b>${people.length}</b> ${people.length === 1 ? "person" : "people"}</span>
      <span><b>${online}</b> online now</span>
      <span><b>${g.channels.length}</b> ${g.channels.length === 1 ? "channel" : "channels"}</span>
      <span>History kept <b>${g.settings.clipTtlHours} h</b></span>
    </div>
    <div class="row" style="margin-top:20px">
      <button class="btn go" data-action="invite" data-channel="${esc(g.channels[0]?.id)}">${ICON.plus}Invite someone</button>
      <button class="btn" data-action="rename-group">Rename</button>
    </div>
    <div class="address">
      <code title="Server address">${esc(g.base)}</code>
      <button class="btn small" data-action="copy" data-text="${esc(g.base)}" data-what="Server address">${ICON.copy}<span class="sr">Copy server address</span></button>
    </div>
  </div>

  ${g.sos.length ? `<section class="block waiting" aria-labelledby="sos-h"><h2 id="sos-h" style="margin:12px 0 4px">SOS in progress</h2>
    <ul class="list">${g.sos.map((x) => `<li><div class="who"><h3>${esc(x.userName)}</h3><p>Sent ${new Date(x.createdAt).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" })}${x.location ? `, <a href="https://maps.google.com/?q=${x.location.lat},${x.location.lng}" target="_blank" rel="noopener">see location</a>` : ""}</p></div></li>`).join("")}</ul></section>` : ""}

  ${waiting.length ? `<section class="waiting" aria-labelledby="wait-h"><h2 id="wait-h" style="margin:12px 0 4px">Waiting for you</h2>
    <ul class="list">${waiting.map((u) => `
      <li><div class="avatar" aria-hidden="true">${esc(initials(u.name))}</div>
        <div class="who"><h3>${esc(u.name)}</h3><p>${u.inviteLabel ? `Invite for ${esc(u.inviteLabel)}, ` : ""}${u.platform === "ios" ? "iPhone" : "Android"}${u.phone ? `, ${esc(u.phone)}` : ""}</p></div>
        <div class="acts stack"><button class="btn small go" data-action="approve" data-id="${esc(u.id)}">Approve</button>
        <button class="btn small" data-action="remove-user" data-id="${esc(u.id)}" data-name="${esc(u.name)}">Reject</button></div>
      </li>`).join("")}</ul></section>` : ""}

  <section class="block" aria-labelledby="ch-h">
    <header><h2 id="ch-h">Channels</h2><button class="btn small" data-action="new-channel">${ICON.plus}New channel</button></header>
    <ul class="list">${g.channels.map((c, i) => `
      <li>
        <span class="preset c-${esc(c.color)}" aria-hidden="true">${i + 1}</span>
        <div class="who"><h3>${esc(c.name)}</h3><p>${c.isDefault ? "Main channel, gets SOS alerts. " : ""}${esc(memberNames(c))}</p></div>
        <div class="acts"><button class="btn small" data-action="invite" data-channel="${esc(c.id)}">Invite</button>
        <button class="btn small quiet" data-action="edit-channel" data-id="${esc(c.id)}">Edit</button></div>
      </li>`).join("")}</ul>
  </section>

  <section class="block" aria-labelledby="ppl-h">
    <header><h2 id="ppl-h">People</h2></header>
    ${people.length ? `<ul class="list">${people.map((u) => `
      <li><div class="avatar" aria-hidden="true">${esc(initials(u.name))}<span class="led ${u.online ? "on" : ""}"></span></div>
        <div class="who"><h3>${esc(u.name)}</h3><p>${u.role === "admin" ? "Admin" : "Member"}, ${u.platform === "ios" ? "iPhone" : "Android"}, ${u.online ? (u.duty ? "online, on duty" : "online, off duty") : "offline"}${u.phone ? `, <a href="tel:${esc(u.phone)}">${esc(u.phone)}</a>` : ""}</p></div>
        <button class="btn small quiet" data-action="edit-user" data-id="${esc(u.id)}">Edit</button>
      </li>`).join("")}</ul>`
    : `<p class="empty">No one has joined yet. Open the admin invite on your phone first, then invite the family.</p>`}
  </section>

  <section class="block" aria-labelledby="inv-h">
    <header><h2 id="inv-h">Open invites</h2></header>
    ${g.invites.length ? `<ul class="list">${g.invites.map((i) => `
      <li><div class="who"><h3>${esc(i.label || (i.admin ? "Admin phone" : "Anyone"))}</h3><p>${i.admin ? "Joins as admin" : esc(chName(i.channelId))}, ${ago(i.expiresAt)}</p></div>
        <button class="btn small quiet danger" data-action="delete-invite" data-id="${esc(i.id)}">Cancel</button></li>`).join("")}</ul>
      <p class="muted small" style="margin-top:10px">For safety, a code is shown only when you create it. Lost one? Cancel it and create a new invite.</p>`
    : `<p class="empty">No open invites.</p>`}
  </section>

  <section class="block" aria-labelledby="set-h">
    <header><h2 id="set-h">Group settings</h2></header>
    <label class="field"><span>Keep voice history for</span>
      <select data-change="ttl">${[1, 6, 12, 24, 48, 72, 168].map((h) => `<option value="${h}" ${h === g.settings.clipTtlHours ? "selected" : ""}>${h === 168 ? "7 days" : h === 1 ? "1 hour" : h + " hours"}</option>`).join("")}</select>
      <small>Messages are deleted automatically after this. SOS recordings stay until you delete the SOS.</small></label>
    <div class="row">
      <button class="btn" data-action="invite-admin">Add an admin phone</button>
      <button class="btn danger" data-action="delete-group">Delete group</button>
    </div>
  </section>`;
}

function settingsView() {
  const s = S.settings || {};
  const apk = s.apk;
  return `<h1 class="display">Console</h1>
  <section class="block" style="margin-top:28px" aria-labelledby="ios-h">
    <header><h2 id="ios-h">iPhone install link</h2></header>
    <form data-form="testflight">
      <label class="field"><span>TestFlight public link</span>
        <input type="url" name="url" inputmode="url" placeholder="https://testflight.apple.com/join/…" value="${esc(s.testflightUrl)}">
        <small>Shown on every invite page so iPhone users can install Hollo.</small></label>
      <button class="btn" type="submit">Save link</button>
    </form>
  </section>
  <section class="block" aria-labelledby="and-h">
    <header><h2 id="and-h">Android app</h2></header>
    <p class="muted">${apk ? `Uploaded ${new Date(apk.updatedAt).toLocaleDateString()} (${(apk.size / 1e6).toFixed(1)} MB). Invite pages offer it for download.` : "Not uploaded yet. Upload hollo.apk so Android phones can install from their invite."}</p>
    <div class="row">
      <label class="btn">${apk ? "Replace hollo.apk" : "Upload hollo.apk"}<input type="file" accept=".apk,application/vnd.android.package-archive" data-change="apk" class="sr"></label>
      ${apk ? `<button class="btn" data-action="copy" data-text="${esc(s.apkUrl)}" data-what="Download link">${ICON.copy}Copy download link</button>` : ""}
    </div>
  </section>
  <section class="block" aria-labelledby="pw-h">
    <header><h2 id="pw-h">Password</h2></header>
    <form data-form="password" style="max-width:420px">
      <label class="field"><span>Current password</span><input type="password" name="current" autocomplete="current-password" required></label>
      <label class="field"><span>New password</span><input type="password" name="password" autocomplete="new-password" minlength="8" required><small>At least 8 characters. Changing it signs out other devices.</small></label>
      <button class="btn" type="submit">Change password</button>
      <p class="err" data-err hidden></p>
    </form>
  </section>
  <button class="btn" data-action="logout">Sign out</button>`;
}

function renderGate() {
  const setup = S.session?.needsSetup;
  $app.innerHTML = `
  <div class="gate"><div class="handset">
    <div class="grill" aria-hidden="true"></div>
    <div class="lcd title" aria-hidden="true"><div class="code">HOLLO</div></div>
    <h1 class="display" style="font-size:44px;margin:24px 0 6px">${setup ? "Set a password" : "Sign in"}</h1>
    <p class="muted" style="margin:0 0 20px">${setup ? "This console controls every group. Choose a password only you know." : "Hollo console"}</p>
    <form data-form="${setup ? "setup" : "login"}">
      <label class="field"><span>Password</span>
        <input type="password" name="password" autocomplete="${setup ? "new-password" : "current-password"}" ${setup ? 'minlength="8"' : ""} required autofocus>
        ${setup ? "<small>At least 8 characters.</small>" : ""}</label>
      <button class="btn go block" type="submit">${setup ? "Set password and open console" : "Sign in"}</button>
      <p class="err" data-err hidden></p>
    </form>
  </div></div>`;
}

// ------------------------------------------------------------------ sheets

function openSheet(html) {
  $sheet.innerHTML = `<div class="sheet-in">${html}</div>`;
  if (!$sheet.open) $sheet.showModal();
  $sheet.querySelector("[autofocus]")?.focus();
}
const closeSheet = () => $sheet.open && $sheet.close();
$sheet.addEventListener("click", (e) => { if (e.target === $sheet) closeSheet(); });

const sheetHead = (title, sub = "") => `<header><div><h2 id="sheet-title">${esc(title)}</h2>${sub ? `<p class="muted small" style="margin:4px 0 0">${esc(sub)}</p>` : ""}</div>
  <button class="icon-btn" data-action="close" aria-label="Close">${ICON.close}</button></header>`;

function inviteForm(channelId, admin = false) {
  const g = S.group;
  openSheet(`${sheetHead(admin ? "Add an admin phone" : "Invite someone", admin ? "Open this on the phone that should manage the group." : g.name)}
  <form data-form="invite">
    <input type="hidden" name="admin" value="${admin ? "1" : ""}">
    <label class="field"><span>Who is it for?</span><input type="text" name="label" maxlength="60" placeholder="${admin ? "My iPhone" : "Ma, Baba, Didi…"}" autofocus><small>Only you see this, to recognise the request.</small></label>
    ${admin ? "" : `<label class="field"><span>Channel</span><select name="channelId">${g.channels.map((c) => `<option value="${esc(c.id)}" ${c.id === channelId ? "selected" : ""}>${esc(c.name)}${c.isDefault ? " (main)" : ""}</option>`).join("")}</select>
      <small>They join this channel once you approve them.</small></label>`}
    <button class="btn go block" type="submit">Create invite</button>
    <p class="err" data-err hidden></p>
  </form>`);
}

function inviteResult(groupName, r, intro) {
  const msg = `Join ${groupName} on Hollo, our private walkie-talkie. Open this on your phone:`;
  openSheet(`${sheetHead(r.invite?.admin ? "Admin invite ready" : "Invite ready", intro)}
    <div class="lcd" role="group" aria-label="Invite code">
      <div class="meta"><span>${esc(r.invite?.admin ? "ADMIN" : r.channelName || "")}</span><span>24 H, ONE USE</span></div>
      <div class="code fresh" aria-label="${esc(r.code.split("").join(" "))}">${esc(r.code)}</div>
    </div>
    <div class="linkbox">${esc(r.url)}</div>
    <div class="share-grid">
      <button class="btn go ${navigator.share ? "" : "wide"}" data-action="copy" data-text="${esc(r.url)}" data-what="Invite link">${ICON.copy}Copy link</button>
      ${navigator.share ? `<button class="btn go" data-action="share" data-title="Join ${esc(groupName)}" data-text="${esc(msg)}" data-url="${esc(r.url)}">${ICON.share}Share</button>` : ""}
      <button class="btn" data-action="copy" data-text="${esc(msg + " " + r.url)}" data-what="Message">Copy message</button>
      <button class="btn" data-action="copy" data-text="${esc(r.code)}" data-what="Code">Copy code</button>
      <button class="btn wide" data-action="qr" data-url="${esc(r.url)}">Show QR code</button>
    </div>
    <div data-qr></div>
    <p class="muted small" style="margin:14px 0 0">The code is shown only now. They'll appear under "Waiting for you" once they join${r.invite?.admin ? " (admin invites skip approval)" : ""}.</p>`);
}

function channelForm(ch) {
  const g = S.group;
  const people = g.users.filter((u) => u.status === "active");
  const members = new Set((ch?.members ?? []).map((m) => m.id));
  openSheet(`${sheetHead(ch ? `Edit ${ch.name}` : "New channel", ch ? "" : "Channels let part of the group talk on their own.")}
  <form data-form="channel" data-id="${esc(ch?.id ?? "")}">
    <label class="field"><span>Name</span><input type="text" name="name" maxlength="30" value="${esc(ch?.name ?? "")}" required autofocus></label>
    <fieldset class="field" style="border:0;padding:0;margin:0 0 16px"><legend style="font-weight:600;margin-bottom:8px">Colour</legend>
      <div class="swatches">${COLORS.map((c) => `<label><input type="radio" name="color" value="${c}" ${(ch?.color ?? "blue") === c ? "checked" : ""}><span class="c-${c}"></span><span class="sr">${c}</span></label>`).join("")}</div></fieldset>
    ${ch?.isDefault ? `<p class="muted small">Everyone who joins the main channel gets this group's SOS alerts.</p>` : people.length ? `<fieldset class="field" style="border:0;padding:0;margin:0 0 16px"><legend style="font-weight:600;margin-bottom:8px">Who's in it</legend>
      <div class="checks">${people.map((u) => `<label><input type="checkbox" name="m" value="${esc(u.id)}" ${members.has(u.id) || u.role === "admin" ? "checked" : ""} ${u.role === "admin" ? "disabled" : ""}>${esc(u.name)}${u.role === "admin" ? ' <span class="muted small">(admin, always in)</span>' : ""}</label>`).join("")}</div>
      <small class="muted">You can also invite new people straight into it.</small></fieldset>` : ""}
    <button class="btn go block" type="submit">${ch ? "Save channel" : "Create channel"}</button>
    ${ch && !ch.isDefault ? `<button class="btn block danger" type="button" data-action="delete-channel" data-id="${esc(ch.id)}" data-name="${esc(ch.name)}" style="margin-top:10px">Delete channel and its history</button>` : ""}
    <p class="err" data-err hidden></p>
  </form>`);
}

function userForm(u) {
  openSheet(`${sheetHead(u.name, u.platform === "ios" ? "iPhone" : "Android")}
  <form data-form="user" data-id="${esc(u.id)}">
    <label class="field"><span>Name</span><input type="text" name="name" maxlength="40" value="${esc(u.name)}" required></label>
    <label class="field"><span>Phone number</span><input type="text" name="phone" inputmode="tel" value="${esc(u.phone ?? "")}" placeholder="+91 98765 43210"><small>Shown to the family during an SOS so they can call.</small></label>
    <label class="field"><span>Role</span><select name="role"><option value="member" ${u.role === "member" ? "selected" : ""}>Member</option><option value="admin" ${u.role === "admin" ? "selected" : ""}>Admin (can invite and approve from the app)</option></select></label>
    <button class="btn go block" type="submit">Save</button>
    <button class="btn block danger" type="button" data-action="remove-user" data-id="${esc(u.id)}" data-name="${esc(u.name)}" style="margin-top:10px">Remove from group</button>
    <p class="err" data-err hidden></p>
  </form>`);
}

function confirmSheet(title, body, label, onYes, typeToConfirm) {
  openSheet(`${sheetHead(title)}<p>${body}</p>
  <form data-form="confirm">
    ${typeToConfirm ? `<label class="field"><span>Type <b>${esc(typeToConfirm)}</b> to confirm</span><input type="text" name="check" autocomplete="off" autofocus></label>` : ""}
    <div class="row"><button class="btn danger grow" type="submit">${esc(label)}</button><button class="btn grow" type="button" data-action="close">Keep it</button></div>
    <p class="err" data-err hidden></p>
  </form>`);
  confirmSheet.cb = async (form) => {
    if (typeToConfirm && form.check.value.trim() !== typeToConfirm) throw new Error(`Type ${typeToConfirm} exactly`);
    await onYes();
  };
}

// ------------------------------------------------------------------ actions

async function refreshGroup() {
  const id = S.group.id;
  S.group = (await api("GET", `/groups/${id}`)).group;
  S.groups = (await api("GET", "/groups")).groups;
  render();
}

const actions = {
  reload: () => load(),
  close: () => closeSheet(),
  copy: (el) => copy(el.dataset.text, el.dataset.what || "Text"),
  share: (el) => share(el.dataset.title, el.dataset.text, el.dataset.url),
  qr: (el) => {
    const box = $sheet.querySelector("[data-qr]");
    if (box.innerHTML) { box.innerHTML = ""; el.textContent = "Show QR code"; return; }
    if (!window.qrcode) return toast("QR code isn't available right now");
    const q = qrcode(0, "M"); q.addData(el.dataset.url); q.make();
    box.innerHTML = `<div class="qr" role="img" aria-label="QR code for the invite link">${q.createSvgTag({ cellSize: 6, margin: 2, scalable: true })}</div>`;
    el.textContent = "Hide QR code";
  },
  "new-group": () => openSheet(`${sheetHead("New group", "Its own people, channels, history and SOS.")}
    <form data-form="new-group"><label class="field"><span>Group name</span><input type="text" name="name" maxlength="30" placeholder="Datta Family" required autofocus>
      <small>Also the name of its main channel.</small></label>
      <button class="btn go block" type="submit">Create group</button><p class="err" data-err hidden></p></form>`),
  invite: (el) => inviteForm(el.dataset.channel),
  "invite-admin": () => inviteForm(null, true),
  "new-channel": () => channelForm(null),
  "edit-channel": (el) => channelForm(S.group.channels.find((c) => c.id === el.dataset.id)),
  "edit-user": (el) => userForm(S.group.users.find((u) => u.id === el.dataset.id)),
  "rename-group": () => openSheet(`${sheetHead("Rename group")}
    <form data-form="rename"><label class="field"><span>Group name</span><input type="text" name="name" maxlength="30" value="${esc(S.group.name)}" required autofocus>
    <small>Phones see the new name straight away.</small></label><button class="btn go block" type="submit">Save name</button><p class="err" data-err hidden></p></form>`),
  approve: async (el) => { await api("POST", `/groups/${S.group.id}/users/${el.dataset.id}/approve`, {}); toast("Approved"); await refreshGroup(); },
  "remove-user": (el) => confirmSheet(`Remove ${el.dataset.name}?`, "Their phone is signed out of this group and their personal channels are deleted. You can invite them again later.", "Remove", async () => {
    await api("DELETE", `/groups/${S.group.id}/users/${el.dataset.id}`); closeSheet(); toast("Removed"); await refreshGroup();
  }),
  "delete-invite": async (el) => { await api("DELETE", `/groups/${S.group.id}/invites/${el.dataset.id}`); toast("Invite cancelled"); await refreshGroup(); },
  "delete-channel": (el) => confirmSheet(`Delete ${el.dataset.name}?`, "Its voice history is deleted too. People stay in the group.", "Delete channel", async () => {
    await api("DELETE", `/groups/${S.group.id}/channels/${el.dataset.id}`); closeSheet(); toast("Channel deleted"); await refreshGroup();
  }),
  "delete-group": () => confirmSheet(`Delete ${S.group.name}?`, "Everyone in it is signed out, and all its channels and history are deleted. This can't be undone.", "Delete group", async () => {
    await api("DELETE", `/groups/${S.group.id}`); closeSheet(); toast("Group deleted"); S.group = null; go("#/");
  }, S.group.name),
  logout: async () => { await api("POST", "/logout", {}); S.session = { signedIn: false, needsSetup: false }; render(); },
};

const forms = {
  setup: async (f) => { await api("POST", "/setup", { password: f.password.value }); S.session = { signedIn: true }; load(); },
  login: async (f) => { await api("POST", "/login", { password: f.password.value }); S.session = { signedIn: true }; load(); },
  "new-group": async (f) => {
    const r = await api("POST", "/groups", { name: f.name.value });
    S.groups = (await api("GET", "/groups")).groups;
    location.hash = `#/g/${r.group.id}`;
    inviteResult(r.group.name, r.adminInvite, "Open this on your own phone first: it makes your phone the group's admin.");
  },
  invite: async (f) => {
    const admin = f.admin.value === "1";
    const r = await api("POST", `/groups/${S.group.id}/invites`, { label: f.label.value, channelId: f.channelId?.value ?? "", admin });
    inviteResult(S.group.name, r, f.label.value ? `For ${f.label.value}` : "");
    refreshGroup();
  },
  channel: async (f) => {
    const id = f.dataset.id;
    const memberIds = [...f.querySelectorAll('input[name="m"]:checked')].map((x) => x.value);
    const body = { name: f.name.value, color: f.color.value };
    if (f.querySelector('input[name="m"]')) body.memberIds = memberIds;
    if (id) await api("PATCH", `/groups/${S.group.id}/channels/${id}`, body);
    else await api("POST", `/groups/${S.group.id}/channels`, body);
    closeSheet(); toast(id ? "Channel saved" : "Channel created"); await refreshGroup();
  },
  user: async (f) => {
    await api("PATCH", `/groups/${S.group.id}/users/${f.dataset.id}`, { name: f.name.value, phone: f.phone.value, role: f.role.value });
    closeSheet(); toast("Saved"); await refreshGroup();
  },
  rename: async (f) => { await api("PATCH", `/groups/${S.group.id}`, { name: f.name.value }); closeSheet(); toast("Renamed"); await refreshGroup(); },
  confirm: async (f) => confirmSheet.cb(f),
  testflight: async (f) => { await api("PUT", "/settings", { testflightUrl: f.url.value }); toast("Link saved"); },
  password: async (f) => { await api("POST", "/password", { current: f.current.value, password: f.password.value }); f.reset(); toast("Password changed"); },
};

document.addEventListener("click", async (e) => {
  const el = e.target.closest("[data-action]");
  if (!el || !actions[el.dataset.action]) return;
  e.preventDefault();
  try { await actions[el.dataset.action](el); } catch (err) { toast(err.message); }
});

document.addEventListener("submit", async (e) => {
  const f = e.target.closest("form[data-form]");
  if (!f || !forms[f.dataset.form]) return;
  e.preventDefault();
  const err = f.querySelector("[data-err]");
  const btn = f.querySelector('button[type="submit"]');
  if (err) err.hidden = true;
  if (btn) btn.disabled = true;
  try { await forms[f.dataset.form](f); }
  catch (x) { if (err) { err.textContent = x.message; err.hidden = false; } else toast(x.message); }
  finally { if (btn && btn.isConnected) btn.disabled = false; }
});

document.addEventListener("change", async (e) => {
  const el = e.target.closest("[data-change]");
  if (!el) return;
  try {
    if (el.dataset.change === "ttl") {
      await api("PATCH", `/groups/${S.group.id}`, { clipTtlHours: Number(el.value) });
      toast("History setting saved");
      await refreshGroup();
    } else if (el.dataset.change === "apk") {
      const file = el.files[0];
      if (!file) return;
      toast("Uploading…");
      await api("PUT", "/apk", file, { fileName: file.name });
      S.settings = await api("GET", "/settings");
      render();
      toast("Android app uploaded");
    }
  } catch (x) { toast(x.message); }
});

load();
