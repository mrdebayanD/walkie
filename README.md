# Walkie

A private push-to-talk walkie-talkie for the family. iPhone and Android, over mobile data, anywhere (Agartala ↔ Seattle works the same as across town).

- **Press and hold** to talk. **Double-tap** for hands-free (tap again to stop; stops by itself after 60 s).
- iPhone: works from the **lock screen** through Apple's Push to Talk.
- **History**: every message is kept for replay, then deleted automatically (default 24 hours; the admin can change it).
- **Channels**: Family is pinned as the default; the admin can add channels for others.
- **Invites**: link, QR code or text message. Nobody joins until the admin approves.
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
