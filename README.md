# P25 APPS — APX1000 PoC (Android)

Android P25/APX1000 proof-of-concept with a real **Codec 2** digital voice
vocoder, so transmitted and received audio has the narrow, synthetic character
of DMR/P25 radios instead of plain VoIP audio.

## Digital voice

The speech path is a genuine low-bitrate vocoder round trip:

```
mic PCM (8 kHz) -> Codec 2 encode -> packed bits -> Codec 2 decode -> speaker
```

- **Codec 2** (LGPL, vendored under `app/src/main/cpp/codec2`) runs natively via
  JNI. Supported bitrates: `700C` (most robotic), `1600` (default, balanced),
  `3200` (clearest), `1300` (narrow) — selectable at runtime from the **Mode**
  button.
- TX keeps a local **sidetone** decode so the operator hears their own digitised
  voice; the encoded frames are also exposed through `PttEngine.onFrameEncoded`
  as the hook for the future WebSocket uplink.
- RX decodes frames and plays them back (the **RX** button replays the last
  transmission to demonstrate the effect without a peer).
- P25/DMR signalling is reproduced: **Talk Permit Tone** (from
  `res/raw/tpt_p25.wav`, via `SoundPool`) is played *before* the mic opens, and a
  **314 Hz talk-inhibit tone** (PCM `AudioTrack`) blocks TX when the channel is
  busy.

## Feature map (per agents.md)

| Requirement | Where |
|---|---|
| Auth: username / password / unique Unit ID | `data/UserStore.kt`, login screen |
| Add channel by name or unique code (`P25-CH-8891`) | `data/Channel.kt`, **Chan +** button |
| APX1000 skin, status bar, Zone/Channel, `ID : XXXX` on RX | `ui/Apx1000View.kt` |
| Signal (Wi-Fi/telephony) + battery indicators | `MainActivity` polls, battery `BroadcastReceiver` |
| 3 softkeys `Chan` / `Scan` / `Cnts`, backlight colours | `ui/Apx1000View.kt` |
| Side PTT: `KEYCODE_PTT` 228, vendor 288/301, `MEDIA_RECORD` | `service/PttService.kt`, `service/PttButtonReceiver.kt` |
| `ForegroundService` + `MediaSessionCompat` + `PARTIAL_WAKE_LOCK` | `service/PttService.kt` |
| TPT before mic, 314 Hz inhibit | `audio/TonePlayer.kt`, `audio/PttEngine.kt` |
| Adaptive layout (HT full-screen, phone dual-mode) | `MainActivity.applyResponsiveLayout()` |

Backlight convention: **Green = RX, Yellow = TX, Red = Inhibit/Busy**.

## Building

Requirements: Android Studio (or CLI) with **Android SDK 34** and **NDK
26.1.10909125** (or adjust `ndkVersion` in `app/build.gradle.kts`).

```bash
# In Android Studio: File > Open > P25-APPS, then Run.
# CLI:
gradle wrapper        # first time, if gradle-wrapper.jar is absent
./gradlew assembleDebug
```

The native library is built by CMake (`app/src/main/cpp/CMakeLists.txt`); no
host tool is needed because the Codec 2 codebooks are pre-generated and
vendored as `codebook*.c`.

## Verifying the native vocoder without an Android SDK

The exact Codec 2 file set used by the NDK build is compiled with host `gcc` and
round-tripped:

```bash
tools/verify_native_host.sh
# 700C  nsam=320  bytes=4   meanAbsErr=7590
# 1600  nsam=320  bytes=8   meanAbsErr=7818
# 3200  nsam=160  bytes=8   meanAbsErr=7369
# 1300  nsam=320  bytes=7   meanAbsErr=7607
# CODEC2_HOST_TEST_PASS
```

## Layout

```
app/src/main/cpp/
  CMakeLists.txt        native build
  codec2_jni.cpp        JNI bridge (Codec2 object)
  codec2/               vendored Codec 2 core + pre-generated codebooks + LGPL license
app/src/main/java/com/p25/apx1000/
  MainActivity.kt       auth, radio UI, indicators, key/PTT wiring
  ui/Apx1000View.kt      scalable APX1000 skin
  audio/Codec2.kt        JNI wrapper
  audio/PttEngine.kt     half-duplex TX/RX pipeline
  audio/TonePlayer.kt    TPT + 314 Hz inhibit
  service/PttService.kt  foreground service, media session, wake lock
  service/PttButtonReceiver.kt
  data/UserStore.kt      accounts / Unit IDs / channels
  data/Channel.kt
tools/                   host vocoder smoke test
```

## Licensing

Codec 2 is licensed under the **GNU LGPL v2.1**; a copy is included at
`app/src/main/cpp/codec2/COPYING.LGPL-2.1`. Because it is dynamically linked
into the app as a shared library, the LGPL relinking obligations are satisfied
by shipping the unmodified sources in this repository.

## Backend (signaling + floor control)

`backend/` is a small Node.js server (Express + `ws`) providing:

- REST: `GET /api/health`, `POST /api/auth/signup`, `POST /api/auth/login`,
  `GET|POST /api/channels`.
- WebSocket `/ws`: `hello` / `join` / `ptt` / `ping` control messages, plus
  opaque **binary Codec 2 frame relay** between talkgroup members. Floor control
  grants the channel to one speaker at a time; others get `reason: "occupied"`.

Audio frames are never decoded server-side (the Android app already does that).

Run it (host port **95** maps to container 9500; Docker's root daemon performs
the privileged bind, so no sudo is needed):

```bash
cd backend
docker compose up -d --build
curl http://localhost:95/api/health
```

### Cloudflare Tunnel

The server already runs a token-managed tunnel (`cloudflared.service`,
tunnel id `65303f17-8921-4e04-b409-ccb7dfafa3e8`). In **Cloudflare Zero Trust →
Networks → Tunnels →** that tunnel → **Public Hostname → Add**:

- Subdomain/host: `p25.dhali.my.id`
- Type: `HTTP`
- URL: `localhost:95`

WebSocket (`wss://p25.dhali.my.id/ws`) works over the same HTTP hostname.
Verified live: `https://p25.dhali.my.id/api/health` → `200`.

## Next steps

- Wire the Android `Transport` to `/ws`: hook `PttService.setFrameSink` for TX
  and `PttEngine.decodeAndPlay` for RX, with `setChannelBusy` driven by the
  server's `floor` messages.
- Replace the local address-book auth with the REST endpoints.

