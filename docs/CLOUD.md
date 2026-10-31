# Hollo on Cloudflare (always on, no server to run)

`cloud/` is the server rebuilt for Cloudflare Workers. Each **group** (a separate
walkie-talkie: its own people, channels, history and SOS) is one Durable Object;
idle groups hibernate, so it fits Cloudflare's free plan for a family.

- Console (create groups and channels, invite and approve people, copy/share invite links): `https://<your-worker>/console/` — works on a phone browser too.
- A group's server address (what the apps use): `https://<your-worker>/g/<groupId>`
- Invite links: `https://<your-worker>/g/<groupId>/join?c=CODE`
- Android installer (upload it in the console): `https://<your-worker>/install/hollo.apk`

The apps speak exactly the same protocol (docs/PROTOCOL.md) as the Go server.

## Deploy

1. Free account at dash.cloudflare.com (no card needed).
2. Workers & Pages › Create › Import a repository › this repo, **root directory `cloud`**.
   Build command: none. Deploy command: `npx wrangler deploy`.
   Every push to `main` then deploys automatically.
3. Open `https://hollo.<your-subdomain>.workers.dev/console/` straight away and set the console password
   (the first visitor sets it).
4. Console › New group › open the admin invite on your phone.

## Push notifications (optional, later)

Set as Worker secrets (Settings › Variables and secrets, type Secret):
`APNS_KEY_P8`, `APNS_KEY_ID`, `APNS_TEAM_ID` (iPhone), `FCM_SERVICE_ACCOUNT` (Android, the JSON).

## Develop

```bash
cd cloud && npm install
npx wrangler dev          # local server on :8787 with local storage
npm test                  # end-to-end test against it (real WebSockets)
npx tsc --noEmit          # type check
```
