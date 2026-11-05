# ts3移动端 (TS3 Mobile)

A native Android TeamSpeak 3 client written in Java. It connects **directly to a
TeamSpeak 3 server** over the client voice protocol (UDP 9987), performs the
full `Init1` ->`clientinitiv` ->`initivexpand2` ->`clientek` ->`clientinit`
handshake, and speaks the normal command protocol. There is no WebSpeak gateway,
WebSocket, join ticket, or HTTP layer in this app.

## What is implemented
- Direct TS3 client handshake and encrypted session (ts3j, vendored).
- Identity generation at security level 8, export/import of TS3 identity text,
  and persistence in `SharedPreferences`.
- Server password, default channel name and optional privilege key.
- The nickname registered with TeamSpeak is `[Android]` followed by the name typed
  in the client. The tag is a fixed label next to the input field, not part of its
  text, so it cannot be selected, deleted or edited; a typed `[Android]` prefix is
  normalised away as well, so the tag can be neither doubled nor changed. It stays
  visible everywhere, including the member list, chat and session events. A fresh
  install starts with a random nickname (`用户1234`) so two new clients do not
  collide.
- Channel directory from the welcome sequence and live channel notifications.
- Channel-name beautification tags rendered as Android spans:
  `[cspacer]`, `[spacer]`, `[rspacer]`, `[lspacer]`, `[center]`, `[left]`,
  `[right]`, `[b]`, `[i]`, `[u]`, `[s]`, `[color=...]`, `[size=...]`,
  `[url]...[/url]`, `[img]...[/img]`, `[hr]`, `[br]` and `[*]`. Spacer channels
  are shown as layout labels and cannot be joined.
- Member directory from client enter/leave/move/update notifications. The
  Channels tab now nests each channel's members and avatars directly under the
  channel row, with a lock marker for protected channels and a generated
  initial-letter avatar when no TeamSpeak avatar is available.
- Channel chat, server chat and private chat, plus poke.
- Chat avatars: TeamSpeak 3/6 avatar files are downloaded from channel 0's file
  area through `ftinitdownload` + the file-transfer port, decoded and shown as
  circular images. The avatar filename works for both the 20-byte TS3 UID and
  the 32-byte TS6 SHA-256 UID; a deterministic initial-letter avatar is used
  when the client has none or the server refuses the transfer.
- Channel join with an optional password (hashed by ts3j).
- Bidirectional Opus voice:
  - `AudioRecord` 48 kHz mono -> Concentus Opus encoder -> ts3j microphone queue.
  - ts3j voice handler -> adaptive jitter buffer -> Concentus Opus decoder -> mixed
    `AudioTrack` playback.
  - Opus in-band FEC and packet-loss concealment for isolated losses; the
    jitter target starts at 60 ms and grows to 160 ms on a lossy path.
  - Runtime microphone permission, mute, speaker mute and echo suppression.
  - Client-side RNNoise 0.2 (xiph/rnnoise master commit `6cbfd53`,
    BSD-3-Clause) runs on the 48 kHz PCM before Opus encoding. It is a
    lightweight recurrent model with the built-in weights compiled into
    `libts3rnnoise.so`; the room settings dialog exposes a 0–100% RNNoise
    strength (wet/dry mix) and a 0–100% VAD noise gate. The platform
    `NoiseSuppressor` is used only when RNNoise is off or unavailable.
  - Optional DeepFilterNet3 is built from the `deep-filter-rt` crate and shipped
    as `libts3dfn.so` for arm64-v8a. It is the fallback AI denoiser when the ONNX
    model below is not installed and the device reports at least six cores; a
    30 ms model applied to every 20 ms frame would otherwise fall behind on a
    four-core phone and drop audio. Below that, on other ABIs, or when the
    library is missing, the lighter RNNoise is used. The settings selector
    persists the choice in `SharedPreferences`, so it survives app restarts.
    DeepFilterNet3 runs at 48 kHz with a 30 ms model delay and a higher CPU cost.
    Its runtime controls are a 0–40 dB maximum attenuation limit (default 30 dB)
    and a 0–100% post-filter (default off).
  - A second AI denoiser, `a2heng/lightweight-denoise-48k` (MIT), runs through
    ONNX Runtime. Its opset-17 export is self-contained — two static inputs
    (`mix_hop` `[1,480]` and a flat `cache_in` `[1,15176]`) and two outputs
    (`enh_hop`, `cache_out`) with the STFT, overlap-add and synthesis envelope
    baked in as constant buffers — so the Android side does no DSP and needs no
    JNI shim: `PureVoxDenoiser` feeds two 10 ms hops per 20 ms frame and holds
    the model's one-hop output delay itself. It becomes the default denoiser when
    the model file is packaged. The weights are not committed; see
    `app/src/main/assets/models/README.md` for the download command and the full
    I/O contract. Without the file the settings entry reads “model not
    installed”, the model is never selected, and the build behaves exactly as
    before.
  - A third denoiser, Alibaba's DFSMN `speech_dfsmn_ans_psm_48k_causal` exported
    to ONNX by `DakeQQ/Audio-Denoiser-ONNX`, is also available. The export is a
    block processor with no cache tensors, but the model is causal, so
    `DfsmnDenoiser` slides a window over the last few hops, runs the graph on it
    and keeps only the last hop of the output: every sample of that hop saw the
    whole window as history, and each output sample is produced exactly once, so
    no crossfade is needed. The window is taken from the model's declared input
    length when it is fixed (it must be a whole number of 20 ms hops, 960 samples
    at 48 kHz) and defaults to four hops otherwise. It is 48 kHz natively, so it
    needs no resampling, and its reference RTF is about 0.01 on a laptop CPU.
    The weights are not committed either; see
    `app/src/main/assets/models/README.md` for the export steps.
  - Denoising is **off by default** and has one master switch, “Enable AI
    denoising”. The model list and every model's parameters are hidden while the
    master is off — there is nothing to choose or tune — and turning it on reveals
    the list plus the selected model's own sliders. Only that model's controls are
    shown, so a DFN3 control never sits next to an RNNoise one.
  - The room settings carry a static microphone level (0–200%) and a three-band
    voice EQ (low shelf 200 Hz, presence peak 1 kHz, high shelf 4 kHz, ±12 dB).
    Both are plain biquads and one multiply in the capture chain, not Android
    `AudioEffect` objects: capture-session effects are silently ignored on many
    phones, while these coefficients give the same response everywhere. The EQ is
    skipped entirely at 0 dB, applies live while the slider moves (coefficients
    are rebuilt on the encoder thread before the next frame), and is bypassed in
    raw microphone mode. The level is a fixed gain, never a dynamics stage — an
    automatic gain here is exactly what used to break the "s" of "四".
  - The encoder follows the channel's codec and quality instead of a fixed
    ladder, which is what a TeamSpeak client is expected to do. ts3j documents
    each codec's per-client bandwidth at quality 0 and 10; that table is
    interpolated per quality step, a 1.7 KiB/s allowance is subtracted for the
    50 packets/s of IP/UDP/TS3 framing, and the remainder becomes the Opus
    bitrate. Opus Voice quality 10 lands near 49 kbps and Opus Music quality 10
    near 83 kbps, so a channel set to Opus Music at full quality finally uses its
    11.87 KiB/s budget instead of the old fixed 32-40 kbps. An Opus Music
    channel also switches the encoder to `OPUS_APPLICATION_AUDIO` and the packet
    codec to `OPUS_MUSIC`. A degraded path still caps the result (48 kbps above
    180 ms RTT, 24 kbps above 300 ms), 10% FEC is reserved, and a
    high-performance Wi-Fi lock plus a 16-frame PCM / 32-packet outgoing queue
    absorb short scheduling bursts. A legacy Speex or CELT channel keeps the
    default profile.
  - Microphone preprocessing is a wind/rumble suppressor, not a gain stage: a
    110 Hz high-pass always runs, and a second 350 Hz corner is crossfaded in
    while low-frequency energy dominates the frame (a gust, or a hand over the
    microphone), so up to 24 dB/oct of rumble is removed only while it is
    actually there. Filtering cannot clip or step the waveform, and it never
    reaches the 5-8 kHz band where a sibilant lives. The earlier AGC and limiter
    were removed because they pumped and clipped poor phone mics. RNNoise
    supplies the broadband suppression (wind hiss, fans, room tone) at an 80% wet
    mix, which keeps some dry detail in a sibilant; the dry path is delayed by
    one 480-sample RNNoise hop, because the overlap-add output is one frame late.
    Without that alignment the wet/dry mix creates a 50 Hz comb
    (metallic/electric sound). Earpiece mode uses raw `MIC`/`UNPROCESSED` capture;
    speaker mode uses communication capture with echo cancellation.
  - Earpiece playback uses `USAGE_MEDIA` with an explicit earpiece device, not
    the narrowband voice-communication route. Speaker mode uses
    `USAGE_VOICE_COMMUNICATION` for echo control. The optional voice
    optimization now uses only a gentle speech-shaping equalizer.
  - QQ-style voice mode (default on) applies only an 8 kHz fourth-order
    low-pass, in both directions: it removes the high-frequency buzz that makes
    a poor phone microphone sound electric, and the same band limit is applied to
    playback, matching QQ's 16 kHz recording path. It used to follow the filter
    with a peak-normalizing limiter and a -49 dB downward gate; that stage
    rescaled each 20 ms frame from its own peak, so the gain stepped at frame
    boundaries with no lookahead. The step landed on the loudest attack of a
    sentence — the "s" of "四" was the reproducible case — and was heard as a
    broken syllable. Both stages are gone; the filter alone cannot clip or
    modulate. Turn the mode off for full-band voice.
- Fast Leave -> Connect waits for the old clientdisconnect to finish, and a
  replacement client hides stale copies of its own UID from the directory.
  After the welcome sequence the client sends `channelsubscribeall`, matching
  the browser client. Without it TeamSpeak only exposes members in the current
  channel, so users disappear as soon as they move elsewhere. The subscription
  is retried after short delays because ts3j has no SDK-equivalent view-ready
  callback. A one-shot `clientlist` then fills in the subscribed roster;
  channel headers show a computed member count and each channel contains its
  member rows. The client also queries its own `clientinfo` after connect
  because the welcome notification may omit `ctid`, which otherwise left every
  channel's count at 0 even though the status line counted the user.
- TeamSpeak server permissions still gate visibility. `clientlist` only returns
  clients in channels the identity can subscribe to, and requires
  `b_virtualserver_client_list` plus sufficient `i_channel_subscribe_power`.
  If the result contains only the local client, the room immediately shows an
  actionable toast and an event-log entry. A privilege key with a higher server
  group can be entered on the connection screen; a client cannot bypass these
  server-side rules.
- Automatic reconnect: ts3j's silence timeout is reduced from 30 s to 6 s,
  the watchdog checks every 1 s, and the first retry is 250 ms (then
  500 ms/1 s/2 s/4 s/8 s, up to 10 attempts). A `ConnectivityManager` callback
  triggers an immediate retry when the default network is lost or comes back,
  instead of waiting for the UDP timeout. Last-channel rejoin, silent-timeout
  watchdog, and no retry for kicks/bans or an explicit user disconnect remain.
- Optional UDP-over-TCP relay for networks that block/throttle UDP; see
  [`relay/README.md`](relay/README.md).
- In-app Chinese/English switch (`中文` default, `English` optional) on both the
  connection screen and the room settings dialog. The choice is persisted and
  applied per activity, independent of the device locale.
- Entering a room keeps the microphone off and shows a one-time privacy dialog:
  `开启麦克风` starts capture (requesting Android's microphone permission if
  needed), while `稍后` leaves it off. The dialog is not repeated by a reconnect
  or language recreation.
- Session event log and a settings dialog for playback volume, RNNoise and language.
- Spoken prompts when a client joins this channel, switches away from it, or
  disconnects from the server. The three clips live in `res/raw` and share one
  switch in the settings dialog. They stay silent while the welcome roster is
  being merged, so entering a busy server does not play one prompt per member.
- The notice is a translucent card drawn over the connection form, not a separate
  screen: the form is already rendered behind it, so dragging the card left reveals
  it instead of a blank window, and dismissing the overlay is the whole transition.
  It shows the running version, the notice that this build is an unauthorised
  derivative of WebSpeak, and the contributor and project credits. The card follows
  the finger, is resisted to the right, and commits past 30% of the width; back
  exits the app rather than lifting the card, which is what back meant before. The
  same page is reachable from the room settings (without the drag, with a back
  button). The version comes from the package manager, so the page never needs
  editing when the build number moves; links are plain HTML anchors handled by the
  platform, with no WebView involved. Touch handling lives in `dispatchTouchEvent`
  so the drag is seen before the `ScrollView`; the child is cancelled only once the
  gesture becomes horizontal, so vertical scrolling and link taps keep working.
  Like the room and the connection form, the page pads its root with the window
  insets: `targetSdk 35` draws edge-to-edge on Android 15, and on older versions the
  inset is 0, so the padding is a no-op there.
- Launcher icon generated from [`ts3j/img/logo.jpg`](ts3j/img/logo.jpg).

## Build

Requirements used for the verified build:

- JDK 21 (`JAVA_HOME`)
- Android SDK with platform 37, build-tools 37.0.0 and NDK 30.0.16248370 (`ANDROID_HOME`)
- CMake 3.22.1 (shipped with the SDK) for the RNNoise native library
- Windows: `gradlew.bat`

```powershell
cd android-ts3
$env:JAVA_HOME = 'C:\Tools\jdk-21'
$env:ANDROID_HOME = 'C:\Android\sdk'
.\gradlew.bat :app:assembleDebug
```

One command instead: `.\package-apk.ps1` auto-detects the Android Studio JDK and
SDK when the variables are unset, deletes the previous `*.apk` files in `dist/`,
builds, and copies each per-ABI APK back as
`TS3Mobile-<version>-<abi>-debug.apk`. `package-apk.bat` is the double-click
entry point for it.

Every APK build increments the version automatically. The counter lives in
[`version.properties`](version.properties) and `app/build.gradle` reads it at
configuration time, so `versionName` becomes `1.5.<build>` and `versionCode`
becomes the same number: one build moves 1.5.1013 to 1.5.1014 with no edit.
Only invocations that ask for `assemble` (or `build`) bump it — IDE sync, clean,
lint and unit tests do not. Commit the resulting `version.properties` change, or
the next build reuses the same number after a checkout.

Release copy: `android-ts3/dist/TS3Mobile-1.5.<build>-debug.apk`

APK: `android-ts3/app/build/outputs/apk/debug/app-debug.apk`
Release copy: `android-ts3/dist/TS3Mobile-1.5.1011-debug.apk`

`minSdk` is 26; `targetSdk` is 35. The app has no AndroidX dependency.

## Desktop protocol verification

The core module has no Android dependency. It was tested against a real
TeamSpeak 3 server 3.13.7 running on `127.0.0.1:9987`:

```powershell
cd android-ts3
.\gradlew.bat :core:coreProbe
# or, with a server already on 127.0.0.1:9987:
.\gradlew.bat :core:directoryProbe
.\tools\verify-core.ps1
```

Expected output ends with `CORE OK`. The probe verifies:

- identity generation, export and re-import;
- direct handshake and connected state;
- welcome channel directory and self/other members;
- channel chat delivery;
- Opus voice sent by one client and decoded by a second client, including the
  adaptive jitter buffer.

The last direct run decoded 363,840 samples with peak RMS 0.164; the same probe
through the TCP relay decoded samples with peak RMS above 0.16. The reconnect
probe was also run with a real server outage:

```text
STATE CONNECTED
EVENT Disconnected: Connection timed out
STATE RECONNECTING
EVENT Reconnecting in 250ms
STATE RECONNECTING
...
STATE CONNECTED
```

The avatar probe connected a second client, uploaded a 41,049-byte PNG with the
SDK, then downloaded it through the core's own `ftinitdownload` path:

```text
HOST uid=X3WSJRcag7dJK4Q4G5BdnLDGE7c= avatarKey=5c29aa36...
file=avatar_fphfjccfbhbkidlhejcliedibljafnjmlamgbdlh
AVATAR BYTES=41049 signature=89504e47
```

This is a desktop JVM test of the same core code the app uses; it is not an
Android device test.

To exercise the relay:

```sh
node relay/server.js
cd android-ts3
$env:TS3_RELAY_HOST='127.0.0.1'; $env:TS3_RELAY_PORT='40000'
.\gradlew.bat :core:coreProbe
```

## Layout

| Path | Purpose |
| --- | --- |
| `ts3j/` | Vendored [ts3j](https://github.com/Manevolent/ts3j) client protocol implementation (Apache-2.0). |
| `core/` | Android-free session, directory, identity and Opus voice engine. |
| `app/` | Android UI, audio capture/playback and `Application` wiring. |
| `relay/` | Optional Node UDP-over-TCP relay server and its README. |
| `core/src/test/java/.../CoreProbe.java` | Live-server integration probe. |
| `core/src/test/java/.../ReconnectProbe.java` | Server-outage watchdog/reconnect probe. |
| `core/src/test/java/.../AvatarProbe.java` | Avatar file-transfer probe. |
| `core/src/test/java/.../DisconnectProbe.java` | Leave latency and server-removal probe. |
| `app/src/main/java/.../ChannelNameFormatter.java` | Channel tag renderer. |
| `app/src/main/java/.../AvatarRepository.java` | Avatar loading, cache and fallback. |

## Layout and voice fixes

- The room top bar now contains only the app name and a compact session line,
  padded by the status-bar/window insets. The bottom bar owns the self
  nickname, microphone, speaker, settings and leave controls.
- A protected channel's password is remembered for the app process and reused
  on rejoin. A rejected remembered password is dropped and the prompt returns.
  Passwords are not written to disk.
- The microphone is attached only after `connect()` returns. ts3j creates its
  20 ms microphone timer inside `setMicrophone()`, so attaching before the
  socket was connected left an unstarted timer that a later attach reused; no
  voice packets were ever produced. Capture also falls back from
  `VOICE_COMMUNICATION` to `MIC`, from 48 kHz to 44.1 kHz with resampling, and
  treats echo/noise-suppression setup as optional.
- Leaving the room previously called ts3j `disconnect()` on the UI thread. That
  method sends `clientdisconnect` and waits up to 30 s for the server state
  change, so the Leave action appeared to do nothing and the activity stayed on
  screen. It now detaches the UI immediately, performs the protocol exchange on
  daemon threads, and force-closes the UDP socket after 2 s. The disconnect
  probe measured `DISCONNECT_MS=0` and server removal after 2 ms.
- Reconnect audio: entering `RECONNECTING` now releases the old `AudioTrack`,
  `AudioRecord` and queued PCM, and a successful reconnect recreates playback
  and restarts microphone capture if it was on. `AndroidAudioEngine` and the
  jitter loop also check their own thread identity, so an old playback, capture
  or jitter thread that was slow to stop cannot publish into the replacement
  session. This fixes the muted/stuck/duplicated audio that could follow an
  automatic reconnect.
- Microphone capture now reads one 960-sample Opus frame (20 ms) at a time and
  runs at Android's audio thread priority. The previous 4096-sample read
  delivered about 85 ms of PCM in one burst, so the encoder produced several
  packets together and the 20 ms sender saw an empty queue between bursts. Other
  clients heard that bursty pacing as choppy speech. The 960-sample read keeps
  capture and packet pacing aligned.
- Capture and encoding are now separate threads with a bounded PCM queue, so a
  slow Opus frame cannot block the next `AudioRecord.read()`. The encoder uses
  Opus complexity 3 instead of the default 9: on the desktop JVM the measured
  average/max was 0.51/2.58 ms at complexity 3 versus 1.82/36.3 ms at
  complexity 9, which matters on phone-class CPU with a 20 ms budget. The
  outgoing Opus queue keeps up to 32 packets and the PCM queue up to 16 frames
  so a short scheduling burst is absorbed instead of cutting a sentence.
- Reconnect sessions now use a connection generation. Leaving during an
  in-flight reconnect invalidates that attempt, and the late socket is closed
  instead of being left connected. The replaced socket is retired with an
  explicit `clientdisconnect` (not just a socket close), its listener is
  removed, and events from a stale socket are ignored. This prevents the old
  server-side client from surviving a reconnect and remaining visible after the
  user clicks Leave.
- Reconnect playback is rebuilt from scratch: the old `AudioTrack` is stopped
  and released, every per-speaker queue is cleared, and a new track is created
  on the `CONNECTED` transition. Reusing the old stream buffer caused choppy
  playback after a network change.
- Voice attachment is serialized and owner-checked. A late failure from an
  abandoned reconnect attempt can no longer call `voiceEngine.detach(old)` on
  the live socket, stop its jitter thread or empty its queues; that regression
  was heard as choppy playback after reconnecting.

## Known limitations

- No Android device or emulator was available in this environment. The APK
  builds and the same core protocol/voice/avatar path passes on the desktop JVM,
  but on-device microphone, speaker routing, permission flow, image decoding and
  Bouncy Castle behavior on ART have not been measured.
- The connection target tested here is TeamSpeak 3.13.7. TeamSpeak 6 was not
  available for a live test; its longer SHA-256 UID is handled by the avatar
  filename helper, but full TS6 interop is unverified.
- Avatars require `b_filetransfer_download` permission and reachable server file
  transfer port (normally TCP 30033). When either is unavailable, chat falls
  back to the generated initial avatar rather than failing the session.
- Guest clients on a default TS3 server are not allowed to run `channellist` or
  `clientlist`; the directory therefore uses the server's welcome sequence and
  notifications, not those commands. A server that suppresses welcome rows
  will show a partial directory.
- The relay is a fallback: TCP head-of-line blocking can make voice latency
  worse than UDP on a merely lossy link. It does not encrypt or authenticate the
  TEAMSPEAK payload beyond TS3's own session encryption; use it only over a
  trusted network/TLS-free VPS you control, with `RELAY_TOKEN` set.
- No foreground service, Bluetooth/audio-device routing, screen sharing, general
  file browser/upload, whisper or TS6 screen-share features. Channel-name tags
  are rendered in the Android channel list, not in the member or chat text.
- Opus uses the pure-Java Concentus codec; no native Opus/NDK build is included.
  Its CPU cost on low-end phones is higher than native libopus.

