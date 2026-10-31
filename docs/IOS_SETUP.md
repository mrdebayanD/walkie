# iPhone app: build and install

Requirements: a Mac with **Xcode 16 or newer** (Xcode 26 gives the iOS 26 Liquid Glass look; older Xcode builds a frosted-glass version), your **paid Apple Developer membership** (active — the "Welcome to the Apple Developer Program" email has arrived), and an iPhone on iOS 16+.

## 1. Generate the Xcode project

```bash
brew install xcodegen
cd ios
```

Open `project.yml` and set:

- `DEVELOPMENT_TEAM` → your Team ID (developer.apple.com › Account › Membership details).
- `PRODUCT_BUNDLE_IDENTIFIER` → keep `com.debayandatta.walkie` or choose your own. It must match `WALKIE_APNS_BUNDLE_ID` on the server.

Then:

```bash
xcodegen
open Walkie.xcodeproj
```

## 2. Capabilities (one time, on the developer site)

developer.apple.com › Certificates, IDs & Profiles › **Identifiers** › **+** › App IDs › App › bundle ID `com.debayandatta.walkie`. Tick:

- **Push Notifications**
- **Push to Talk**
- **Time Sensitive Notifications** (lets SOS alerts break through Focus)

In Xcode, select the Walkie target › Signing & Capabilities › "Automatically manage signing" with your team. The entitlements file already requests Push, Push to Talk and Time Sensitive.

**Critical Alerts (optional, for SOS ringing even on silent):** Apple grants this only on request, mainly to health, safety and public-safety apps, so a personal app may be refused. Request at developer.apple.com/contact/request/notifications-critical-alerts-entitlement/. If approved, uncomment the key in `Walkie/Walkie.entitlements` and set `WALKIE_APNS_CRITICAL=1` on the server. Without it, SOS uses time-sensitive alerts repeated every 30 seconds until someone responds.

## 3. Run on your phone

Plug in your iPhone, choose it as the run destination, press **Run**. The first build may show a few compile errors (the code was written without Xcode); they're usually one-line fixes — paste them back to Claude.

Debug builds from Xcode use Apple's **sandbox** push service; the app tells the server this automatically.

## 4. Give it to the family (TestFlight)

1. [App Store Connect](https://appstoreconnect.apple.com) › Apps › **+** › New App › iOS, name "Walkie" (or any free name), the bundle ID above, SKU `walkie`.
2. In Xcode: Product › **Archive** › Distribute App › **TestFlight & App Store** › Upload.
3. App Store Connect › TestFlight:
   - **Internal testing** (up to 100 people who are on your developer team): fastest, no review. Each person needs to be added under Users and Access first.
   - **External testing** (up to 10,000, by email or a public link): needs a short beta review the first time (usually about a day).
4. Family installs the **TestFlight** app from the App Store, taps your invite, then installs Walkie.

TestFlight builds expire after **90 days**: upload a new build before then (a calendar reminder every ~80 days helps).

## Automatic TestFlight uploads (recommended)

Once set up, every change to the iPhone app builds, signs and uploads itself to TestFlight (GitHub Actions › **TestFlight**). You never need to archive in Xcode.

One-time setup, about 15 minutes, after your Apple Developer membership is **active**:

1. **App ID with capabilities** — developer.apple.com › Certificates, IDs & Profiles › Identifiers › **+** › App IDs › App › Bundle ID `com.debayandatta.walkie`. Tick **Push Notifications**, **Push to Talk**, **Time Sensitive Notifications**. Register.
2. **App record** — [App Store Connect](https://appstoreconnect.apple.com) › Apps › **+** › New App: iOS, name `Walkie` (if taken, e.g. `Walkie Family`), language English, bundle ID `com.debayandatta.walkie`, SKU `walkie`. Access: Full.
3. **API key** — App Store Connect › Users and Access › **Integrations** › App Store Connect API › Team Keys › **+**. Name `GitHub`, access **App Manager**. Download the `.p8` (only once). Note the **Key ID** and the **Issuer ID** shown at the top.
4. **Team ID** — developer.apple.com › Account › Membership details.
5. **GitHub secrets** — github.com/mrdebayanD/walkie › Settings › Secrets and variables › Actions › **New repository secret**, four times:

   | Name | Value |
   | --- | --- |
   | `APPLE_TEAM_ID` | your 10-character Team ID |
   | `ASC_KEY_ID` | the key's Key ID |
   | `ASC_ISSUER_ID` | the Issuer ID |
   | `ASC_KEY_P8` | the whole contents of the `.p8` file (open it in TextEdit, copy everything including the BEGIN/END lines) |

6. GitHub › Actions › **TestFlight** › **Run workflow**. About 10 minutes later the build appears in App Store Connect › your app › TestFlight (Apple then "processes" it for 5–30 minutes).

### Getting it onto phones

- **You (and anyone on your developer team)**: TestFlight › **Internal Testing** › create a group, add yourself. Install the **TestFlight** app on your iPhone; Walkie appears there. No review.
- **Family (external testing)**: TestFlight › **External Testing** › create a group "Family" › add the build. The first build needs Apple's **beta review** (usually under a day; fill in "What to test" and a contact). Then turn on **Public Link** and copy it (looks like `https://testflight.apple.com/join/AbCd1234`).
- Put that link on the server so invite pages show it: add `WALKIE_TESTFLIGHT_URL=https://testflight.apple.com/join/AbCd1234` to `/etc/walkie/walkie.env` and restart.

TestFlight builds expire after 90 days; any change to `ios/` uploads a fresh one, or press Run workflow.

## How it behaves

- **On duty** (Family tab › This phone) joins Apple's Push to Talk channel: a talk button appears on the lock screen and in the Dynamic Island, and the phone wakes when someone talks. Off duty: you only hear people while the app is open.
- Talk button: **press and hold** (starts after 0.3 s, so brushing the screen does nothing); **double-tap** for hands-free; tap to stop. Hands-free stops itself after 60 seconds.
- **Mute speaker**: the phone vibrates and shows who's talking instead of playing; messages stay in History.
- The app never uses location except during an SOS, and the microphone only while you talk.
