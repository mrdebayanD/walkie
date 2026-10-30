# Walkie protocol (v1)

This is the contract between the server and both apps. If you change one side, change the other and this file.

All REST calls are JSON over HTTPS. Authenticated calls send `Authorization: Bearer <token>`.
Errors come back as `{"error": "<code>", "message": "<human text>"}` with a 4xx/5xx status.

## Audio format

| Property | Value |
| --- | --- |
| Codec | Opus, mono |
| Sample rate | 48 000 Hz |
| Frame | 20 ms (960 samples) per Opus packet |
| Bitrate | 16 kbps target, VBR, voice signal |
| Packets per WebSocket message | up to 3 (60 ms), fewer at the end of a transmission |

### Binary WebSocket message (client → server)

```
byte 0       : 0x01 (audio)
then repeat  : uint16 big-endian packet length, packet bytes
```

### Binary WebSocket message (server → client)

```
byte 0       : 0x01 (audio)
bytes 1..4   : txId, uint32 big-endian
then repeat  : uint16 big-endian packet length, packet bytes
```

### Clip file (`GET /v1/clips/{id}/audio`)

```
bytes 0..3   : "WKC1"
bytes 4..7   : sample rate, uint32 big-endian (48000)
bytes 8..9   : frame duration in ms, uint16 big-endian (20)
then repeat  : uint16 big-endian packet length, packet bytes
```

## Joining (no SMS, no passwords)

1. The admin creates an invite (`POST /v1/invites`). The server returns a one-time `code` and a `url` like `https://walkie.example.org/join?c=ABCD2345XY`.
2. The new person opens the link or scans its QR code. The web page offers an **Open in Walkie** button (`walkie://join?s=<server>&c=<code>`) and install help.
3. The app calls `POST /v1/join` with the code and the person's name. It gets a token at once, with `status: "pending"`.
4. The admin approves (`POST /v1/users/{id}/approve`). The person is added to the invite's channel and becomes `active`.

The very first admin is created on the server with `walkie setup`, which prints an admin invite link (auto-approved).

Invite codes: 10 characters (Crockford base32), one use, expire after 24 hours. Tokens: 32 random bytes; the server stores only their SHA-256.

## REST endpoints

| Method and path | Who | Body | Returns |
| --- | --- | --- | --- |
| `GET /healthz` | anyone | | `ok` |
| `GET /join?c=CODE` | anyone | | HTML page with open/install help |
| `POST /v1/join` | anyone (rate limited) | `{code, name, platform: "ios"\|"android"}` | `{token, user}` |
| `GET /v1/me` | any token | | `{user, settings, server}` |
| `PUT /v1/me` | any token | `{name?, phone?}` (phone: digits, optional leading +) | `{user}` |
| `PUT /v1/me/push` | any token | `{apnsPtt?, apnsAlert?, fcm?, apnsSandbox?}` | `{ok}` |
| `GET /v1/users` | active | | `{users}` (admin also sees pending) |
| `POST /v1/users/{id}/approve` | admin | | `{user}` |
| `DELETE /v1/users/{id}` | admin | | `{ok}` |
| `GET /v1/channels` | active | | `{channels}` (with members and presence) |
| `POST /v1/channels` | admin | `{name, color, memberIds}` | `{channel}` |
| `PATCH /v1/channels/{id}` | admin | `{name?, color?}` | `{channel}` |
| `PUT /v1/channels/{id}/members` | admin | `{memberIds}` | `{channel}` |
| `DELETE /v1/channels/{id}` | admin | | `{ok}` (not the default channel) |
| `POST /v1/invites` | admin | `{channelId, label}` | `{invite, code, url, appUrl}` |
| `GET /v1/invites` | admin | | `{invites}` (unused, unexpired) |
| `DELETE /v1/invites/{id}` | admin | | `{ok}` |
| `GET /v1/channels/{id}/clips` | member | | `{clips}` newest first |
| `GET /v1/clips/{id}/audio` | member | | clip file |
| `GET /v1/settings` | active | | `{settings}` |
| `PUT /v1/settings` | admin | `{clipTtlHours}` (1–168) | `{settings}` |
| `POST /v1/sos` | active | `{lat?, lng?, accuracy?}` | `{sos}` |
| `GET /v1/sos` | active | | `{sos}` (active first, then recent log) |
| `POST /v1/sos/{id}/location` | sender | `{lat, lng, accuracy}` | `{sos}` |
| `POST /v1/sos/{id}/ack` | recipient | | `{sos}` (stops repeat alerts for you) |
| `POST /v1/sos/{id}/respond` | recipient | | `{sos}` ("I'm responding") |
| `POST /v1/sos/{id}/resolve` | sender or admin | `{resolution: "safe"\|"false_alarm"}` | `{sos}` |
| `DELETE /v1/sos/{id}` | admin | | `{ok}` (deletes the log entry and its clip) |

## WebSocket `GET /v1/ws`

Authenticated with the bearer header. Only `active` users may connect. Text messages are JSON with a `t` field.

### Client → server

| `t` | Fields | Meaning |
| --- | --- | --- |
| `hello` | `duty: bool, app: string` | First message. `duty` = on duty (wants live audio). |
| `duty` | `on: bool` | Change on-duty state. |
| `floor_request` | `channel, mode: "hold"\|"handsfree"\|"sos", sosId?` | Ask to talk. |
| `floor_release` | | Stop talking. |
| `ping` | | Keepalive (send every 4 minutes when idle). |

### Server → client

| `t` | Fields | Meaning |
| --- | --- | --- |
| `welcome` | `userId, active: [{channel, txId, userId, startedAt}]` | After hello. Active transmissions follow as `floor_taken` + catch-up audio. |
| `floor_granted` | `channel, txId, maxMs` | You may stream audio now. |
| `floor_denied` | `channel, reason: "busy"\|"not_member"\|"bad_request", holder?` | |
| `floor_taken` | `channel, txId, userId, name, mode, startedAt` | Someone started talking. Audio frames with this txId follow. |
| `floor_ended` | `channel, txId, userId, clipId, durationMs, reason` | Transmission over. `reason`: `released`, `timeout`, `disconnected`, `idle`, `preempted`. |
| `presence` | `userId, online, duty` | A co-member's state changed. |
| `sos` | `sos` | New SOS or an update to one (location, responders, resolved). |
| `channels_changed` | | Refetch `GET /v1/channels`. |
| `settings_changed` | `settings` | |
| `pong` | | |
| `error` | `code, message` | |

### Floor rules

- One talker per channel. A request while busy gets `floor_denied: busy`.
- Limits: `hold` 120 s, `handsfree` 60 s, `sos` 15 s. The server ends the transmission at the limit (`reason: timeout`).
- No audio for 10 s during a transmission ends it (`reason: idle`).
- An `sos` request pre-empts whoever is talking (`reason: preempted`).
- Disconnecting ends your transmission (`reason: disconnected`).

## Push

- iPhone talk pushes go only to members **not connected** to the WebSocket at that moment.
- Android talk pushes go to **every** Android member except the talker, because Doze can freeze a socket that still looks connected. The app ignores the push when it is already playing, and reconnects (getting a replay) when nothing has arrived after 2 seconds.
- SOS pushes go to every recipient who hasn't acknowledged, connected or not.

| Event | iPhone | Android (FCM data message, high priority) |
| --- | --- | --- |
| Someone starts talking | APNs `pushtotalk` to the PTT token: `{"aps":{}, "type":"tx", "channel", "txId", "speaker"}` | `{type: "tx", channel, txId, speaker}` |
| SOS | APNs alert, `interruption-level: critical` if `WALKIE_APNS_CRITICAL=1` else `time-sensitive`, repeated every 30 s (max 10) until acknowledged | `{type: "sos", sosId, sender}`, repeated the same way |
| SOS resolved | APNs alert `time-sensitive` | `{type: "sos_resolved", sosId, sender, resolution}` |

## History

Every transmission is saved as a clip. Clips older than `clipTtlHours` (default 24) are deleted by a sweeper every 10 minutes. SOS clips are kept until the admin deletes the SOS log entry.
