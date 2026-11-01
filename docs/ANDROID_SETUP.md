# Android app: build and install

Free. No Play Store account needed — you install the app file (APK) directly.

## 1. Build

Install [Android Studio](https://developer.android.com/studio) (free), then:

1. Put `google-services.json` from Firebase in `android/app/` (see docs/DEPLOY.md, "Android (Firebase Cloud Messaging)"). Without it the app still builds and works while open or on duty, but can't be woken from deep sleep.
2. Android Studio › **Open** › the `android` folder. Let it sync (it downloads Gradle and the libraries the first time).
3. Build › **Build APK(s)**, or from a terminal:

```bash
cd android
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

The release build is signed with Android Studio's debug key so it installs anywhere. Keep using the same computer to build updates (a different key means uninstalling first). Before ever putting it on the Play Store, switch to your own keystore in `app/build.gradle.kts`.

The first build may show a few compile errors (the code was written without the Android SDK); paste them back to Claude.

## Easiest: download the ready-made APK

Every change builds automatically. Permanent download link (no GitHub account needed): **https://github.com/mrdebayanD/walkie/releases/download/android-latest/walkie.apk** — it always serves the newest build; the release page shows its build number.

To let family install it **from their invite link** without GitHub, put the APK on the server:

```bash
scp walkie.apk ubuntu@SERVER_IP:~/
ssh ubuntu@SERVER_IP 'sudo install -o root -g walkie -m 0644 walkie.apk /etc/walkie/walkie.apk'
# in /etc/walkie/walkie.env:  WALKIE_ANDROID_APK=/etc/walkie/walkie.apk   then: sudo systemctl restart walkie
```

The invite page then shows **Android: download Hollo** above the **Open in Hollo** button.

## 2. Install on a family phone

1. Send `app-release.apk` by WhatsApp, Google Drive or a USB cable.
2. Open it on the phone. Android asks to allow "Install unknown apps" for that app (WhatsApp/Files/Drive) — allow it once.
3. Open Hollo, then open the invite link you sent them (or paste it).

## 3. The battery settings (important on budget phones)

Brands common in India (Xiaomi/Redmi/Poco, Vivo/iQOO, Oppo, Realme, OnePlus, Samsung) stop background apps aggressively. The app shows a guide on first launch with the exact steps for that phone's brand:

- **Allow unrestricted battery** (the app asks Android directly).
- **Autostart / background activity**: brand-specific; the guide lists the menu path.

Do this in person when you install it — it's the difference between "always works" and "works when the app is open".

## How it behaves

| Mode | What it does | Battery |
| --- | --- | --- |
| **On duty** (default) | A small notification "Hollo is on duty" keeps one quiet connection open; you hear people live, even locked | Very low: no polling, one keep-alive every 4 minutes |
| **Low-power mode** | No connection in the background; a push wakes the app when someone talks and it plays from the start (1–3 s late) | Lowest |
| **Off duty** | You only hear people while the app is open | None |

- Talk button: press and hold (after 0.3 s); **double-tap** for hands-free; tap to stop; stops by itself after 60 s.
- After a phone restart, Android doesn't allow the app to turn its microphone service back on by itself: a notification asks the person to tap once to go back on duty. Pushes still wake it meanwhile.
- SOS alerts use the alarm sound and appear over the lock screen. On Android 14+, showing over the lock screen needs "Full screen notifications" allowed for Hollo (Settings › Apps › Hollo › Notifications); otherwise it's a loud heads-up notification.
- Works on Android 8.0+ and is designed for 2 GB RAM phones: the Opus codec is pure Java (`android/concentus`, BSD licence), no extra native libraries.
