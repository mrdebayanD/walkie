# Quick test on your Mac (no server setup)

Try everything before setting up Oracle: run the server on your Mac and give it a temporary public HTTPS address with a free Cloudflare quick tunnel (no account needed). Phones anywhere, on any network, can reach it while your Mac is awake.

## 1. Start the server

```bash
chmod +x walkie-darwin-arm64          # Intel Mac: walkie-darwin-amd64
xattr -d com.apple.quarantine walkie-darwin-arm64 2>/dev/null   # macOS blocks downloaded binaries otherwise
brew install cloudflared
cloudflared tunnel --url http://localhost:8080
```

Cloudflared prints an address like `https://random-words.trycloudflare.com`. Leave it running, open a second Terminal tab:

```bash
WALKIE_PUBLIC_URL=https://random-words.trycloudflare.com WALKIE_DATA=./walkie-data ./walkie-darwin-arm64 serve
```

It prints your admin invite link. Check `https://random-words.trycloudflare.com/healthz` in a browser says `ok`.

## 2. Install the apps

- **iPhone**: open `ios/` in Xcode as in docs/IOS_SETUP.md and Run on your phone. Open the admin link on the phone, Open in Walkie, Join.
- **Android**: install the APK from the GitHub Actions "Android" run (or build it, docs/ANDROID_SETUP.md). Invite it from your iPhone: Family › Family channel › Add people.

## Limits of this setup

- The tunnel address changes every time you restart `cloudflared`. Invites and joined phones use the old address, so keep it running for the whole test session (if it changes: Leave this family on each phone and join again).
- No push yet unless you add the APNs/Firebase settings (docs/DEPLOY.md step 4) as environment variables. Without push: phones hear each other while the app is open or on duty (Android) — the lock-screen wake-up on iPhone needs APNs.
- When your Mac sleeps, the server stops.
