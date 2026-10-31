# Walkie

A private push-to-talk walkie-talkie for the family. iPhone and Android, over mobile data, anywhere (Agartala ↔ Seattle works the same as across town).

- **Press and hold** to talk. **Double-tap** for hands-free (tap again to stop; stops by itself after 60 s).
- iPhone: works from the **lock screen** through Apple's Push to Talk.
- **History**: every message is kept for replay, then deleted automatically (default 24 hours; the admin can change it).
- **Channels**: Family is pinned as the default; the admin can add channels for others. People are added from inside each channel.
- **Talk to one person**: tap someone's picture to talk to them only. Nobody else (not even the admin) can hear or replay it in the app.
- **History per channel**, with unheard counts.
- **Invites**: link, QR code or text message, always into a specific channel. Nobody joins until the admin approves.
- **Phone numbers**: asked at sign-up; one-tap calls from the member list and during SOS.
- **SOS**: hold 3 s, then a 5 s countdown with Cancel. Sends a loud alert, live location and a 15-second clip to the family. Sender taps "I'm safe" to close it.
- Built for old phones: idle cost is near zero (no polling, mic only while talking, location only during SOS).

## What's in here

| Folder | What | Language |
| --- | --- | --- |
| `server/` | Relay server: voice relay, floor control (one talker at a time), history, invites, SOS, push | Go (one ~9 MB binary, ~10 MB RAM when idle) |
| `ios/` | iPhone app | Swift / SwiftUI, iOS 16+ |
| `android/` | Android app | Kotlin / Jetpack Compose, Android 8+ |
| `deploy/` | Server install script, systemd service, HTTPS proxy config | |
| `docs/` | Setup guides and the protocol both apps speak | |

## Setup, in order

0. **Just trying it?** [docs/QUICK_TEST.md](docs/QUICK_TEST.md) — run the server on your Mac with a free temporary HTTPS address.
1. **Server** (free): [docs/DEPLOY.md](docs/DEPLOY.md) — Oracle Cloud Always Free VM + free DuckDNS name + automatic HTTPS.
2. **iPhone app**: [docs/IOS_SETUP.md](docs/IOS_SETUP.md) — needs your Apple Developer membership; family installs through TestFlight.
3. **Android app**: [docs/ANDROID_SETUP.md](docs/ANDROID_SETUP.md) — free; install the APK directly.
4. **Family**: [docs/FAMILY_GUIDE.md](docs/FAMILY_GUIDE.md) — inviting, approving, what each person needs to tap.
5. **Before trusting it**: [docs/TESTING.md](docs/TESTING.md) — the checklist to run on real phones.

## Costs

| Item | Cost |
| --- | --- |
| Server (Oracle Always Free) | $0 (Oracle asks for a card to verify identity; Always Free resources are not charged) |
| Domain (DuckDNS) and HTTPS (Let's Encrypt) | $0 |
| Android push (Firebase Cloud Messaging) | $0 |
| Apple Developer Program | $99 / year (already paid) |

## Status

- Server: built and covered by automated tests (`cd server && go test -race ./...`).
- iPhone and Android apps: complete source, **not yet compiled** — they were written without access to Xcode or the Android SDK. Expect a few compile fixes on first build; see [docs/TESTING.md](docs/TESTING.md).

## Automatic builds (GitHub Actions)

Push this repo to GitHub and every change builds automatically (Actions tab → pick a run → Artifacts):

| Workflow | Produces |
| --- | --- |
| Server | tests + `walkie-linux-arm64`, `walkie-linux-amd64`, `walkie-darwin-arm64` |
| Android | `app-release.apk`, ready to install (add the `GOOGLE_SERVICES_JSON` secret for push) |
| iOS | an unsigned compile check + build log (installing on a phone still goes through Xcode or TestFlight) |
