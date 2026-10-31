# Test checklist

## What is already verified

- **Server**: automated end-to-end tests run against a real server and real WebSocket connections (`cd server && go test -race ./...`): joining with invites (single use, expiry, approval, rate limiting), live audio relay, one-talker-at-a-time, catch-up for a phone that connects mid-message, history and the 24-hour auto-delete, SOS (alerts, repeats, responders, closing, SOS clips kept), SOS pre-empting a talker, push targeting (iPhone only when offline, Android always), permissions, phone numbers. Also cross-compiled for Linux Arm and AMD servers.
- **Android audio settings**: the bundled Opus encoder was run with the app's exact settings: ~42 bytes per 20 ms packet (~17 kbps), ~1.3 ms to encode each 20 ms frame on a server CPU.

## What could not be verified here (do these on real phones)

The apps were written without Xcode or the Android SDK, so they have **not been compiled or run**. Expect a handful of compile errors on the first build; send them back and they'll be fixed.

### iPhone — first build

- [ ] App launches; Join with the admin link; you're admin.
- [ ] Push to Talk: Family tab › On duty → lock the phone → the talk button shows on the lock screen.
- [ ] Opus check: hold to talk to an Android phone and back. If iPhone ↔ Android audio is silent or garbled, the Apple Opus encoder settings (`ios/Walkie/Audio/OpusCodec.swift`) need adjusting — report what you hear.
- [ ] History replay while on duty and idle (the app activates the audio session itself here; confirm it plays).

### Both platforms — the real-world list

- [ ] Hold to talk: starts after a short press, not on a brush; release sends.
- [ ] Double-tap: hands-free; tap stops; auto-stops at 60 s.
- [ ] While someone talks, others see "Busy" and can't talk over.
- [ ] Locked phone hears a message (iPhone on duty; Android on duty and battery guide done).
- [ ] Phone asleep for 30+ minutes, then a message: arrives (Android: tests Doze).
- [ ] Airplane mode on/off: reconnects within seconds.
- [ ] Missed message shows in History with a blue dot; "Play missed" plays them oldest first.
- [ ] Clip auto-delete (set it to 1 hour in Family › This phone, wait).
- [ ] SOS: hold 3 s → countdown → Cancel works. Then a real one: every phone alerts, location opens in Maps, the clip plays, "I'm responding" shows for the sender, "I'm safe" clears it everywhere.
- [ ] Mute speaker: vibrates instead of playing.
- [ ] Remove a member: their app goes back to the join screen.
- [ ] Battery: leave a phone on duty overnight; note battery used by Hollo in Settings › Battery.
- [ ] Network: test on Jio, Airtel and BSNL in a few parts of Agartala.
