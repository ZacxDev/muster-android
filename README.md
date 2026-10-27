# muster-android

The native Android app for [muster](https://github.com/ZacxDev/muster): a
Kotlin + Jetpack Compose shell that hosts muster's web UI in a WebView, plus
native modules for actionable notifications, bubbles, widgets and live status.
Push goes through [UnifiedPush](https://unifiedpush.org) with a self-hosted
[ntfy](https://ntfy.sh) distributor. No Google Play services, no FCM.

The design is muster's
[`docs/design/android.md`](https://github.com/ZacxDev/muster/blob/main/docs/design/android.md).
This repo follows it.

**Status: Phase 1** (shell and push). The app hosts muster's web UI, pairs
with muster for a device credential and a biometric step-up key, and shows
muster's push notifications through UnifiedPush. It has no action buttons
yet: signed actions are Phase 2. Phase 0's spike results are in
[`docs/phase0-results.md`](docs/phase0-results.md).

### What Phase 1 does

- **First run:** asks for the muster server's `https://` origin. The app opens
  only that origin; other `https` links open in a Custom Tab.
- **The shell:** a hardened WebView (no file access, no
  `addJavascriptInterface`) with password sign-in on muster's own page. The
  native container pads for the system bars, cutout and keyboard, because CSS
  `env(safe-area-inset-*)` reads 0 in a WebView (Phase 0, S12).
- **Passkeys:** the WebView runs WebAuthn as this app
  (`WEB_AUTHENTICATION_SUPPORT_FOR_APP`, where the WebView has the feature),
  so muster's passkey sign-in and its "Confirm it's you" passkey work in the
  shell. Android uses a passkey only if the server lists this package and the
  signing certificate in `/.well-known/assetlinks.json`, and the server's
  WebAuthn backend must accept the app's origin,
  `android:apk-key-hash:<unpadded base64url SHA-256 of the signing cert>`.
  For the release certificate below that is
  `android:apk-key-hash:9mAiKkY2M_bmWoehcB2eyGI2S6Rkno4J00uBca0g9eY`. A
  debug-signed build has a different certificate, and so a different origin:
  it cannot use passkeys against production. AGP's debug key is a throwaway
  generated per machine (per CI runner), so 🔴 never add a debug key's hash to
  production's `assetlinks.json` or `GOTRUE_WEBAUTHN_RP_ORIGINS`.
  Passkey autofill (conditional mediation) does not work in a WebView.
- **In the shell:** back follows the WebView's history; `<input type=file>`
  opens the system picker and hands the page `content://` URIs only; muster's
  `alert`/`confirm` are native dialogs. A URL the shell cannot parse is
  refused, never waved through.
- **The microphone (dictation):** the page gets audio capture only when the
  request comes from exactly the muster origin and asks for audio alone;
  camera, MIDI, protected media and every other origin are denied. The app
  asks Android for RECORD_AUDIO the first time the page wants it. When
  Android answers "no" without a rationale after an earlier ask (it showed no
  prompt after "Don't allow", or the user dismissed its prompt twice; the app
  cannot tell which), a dialog says the microphone isn't allowed and offers
  the app's settings page instead of a silent refusal.
- **The bridge:** `musterApp`, a `WebViewCompat.addWebMessageListener`
  restricted to the exact origin, main frame only. It understands the two
  messages muster sends, `pair` and `open-notification-settings`, and
  `touch-start`, which the app's own pull-to-refresh script sends (below).
- **Pull to refresh:** pulling down at the top of a page reloads it, with a
  spinner in muster's colours until the page finishes (15 s at most). A pull
  refreshes only when nothing under the finger can still scroll up. The
  WebView's own scroll position cannot tell: muster's agent page scrolls its
  chat log inside a document that does not scroll. So a document-start script
  (`WebViewCompat.addDocumentStartJavaScript`, the exact origin only, main
  frame only) reports at each `touchstart` how far the document and the
  scrollers under the finger are scrolled, and whether the finger is on an
  overlay (a sheet, a dialog, the open sidebar) or on something that takes its
  own drags, and whether a non-empty text field has focus:
  `{"type":"touch-start","doc":"k3x9q2","seq":3,"doc_top":0,"inner_top":null,"owner":"page","editing":false}`.
  `doc` and `seq` name the gesture (a random id per document, and its count
  of gestures); the app counts DOWNs the same way and uses a report only for
  the gesture it describes, so a late one never decides the next pull. It
  carries scroll offsets, that count and the `editing` boolean only: no text,
  nothing typed, no URL, and nothing goes back to the page. A pull never
  reloads while a text field (input, textarea, contenteditable) has text in
  it. A sideways swipe (a chip row), a second finger, the terminal and a
  mirrored session (their path segments decoded first) never refresh. A
  WebView without document-start scripts never refreshes.
- **Pairing:** Account → Devices → **Pair this phone** hands the app a
  one-time code; the app asks you to confirm, re-checks the code's expiry,
  makes the step-up key, and redeems the code at `POST /api/device/pair`.
  Re-pairing redeems first and removes the old pairing only once the new one
  exists.
- **The step-up key:** EC P-256 in the Android Keystore, a fresh strong
  biometric on every use, destroyed by a new enrolment. A phone without a
  strong biometric offers a biometric-or-PIN key, which muster accepts only
  if its PIN-key policy is on; otherwise the phone pairs read-only. A
  software-only key is never sent. The app never falls back from one kind to
  the other without saying so.
- **The credential:** stored only in a DataStore file sealed with AES-256-GCM
  under a Keystore key, in `noBackupFilesDir`, with backup off.
- **Push:** UnifiedPush through the ntfy app. The app registers with muster's
  VAPID key, checks that the endpoint's host is exactly the operator's push
  host (else it says how to set ntfy's default server), and registers the
  endpoint with muster, which sends it a test push first.
- **🔴 Only decrypted pushes are trusted.** ntfy does not check VAPID on
  `up*` topics, so the app drops every message it could not decrypt before
  reading it. Then payload v2 (at most 3993 bytes, one 4096-byte encrypted
  record) is parsed strictly.
- **Notifications:** WHO and WHERE only, `VISIBILITY_PRIVATE` with a public
  version, on the Asks and approvals or Tasks channel; `resolved` cancels by
  tag. POST_NOTIFICATIONS is asked for right after pairing.
- **Phone settings** (long-press the launcher icon): pairing, step-up key,
  push state, unpair.
- **Opening (0.1.2):** the launcher shows the home page's last frame until
  the live page paints over it. The frame is a JPEG of the board, so it is a
  new copy of board text at rest: app-private, in `noBackupFilesDir` (never
  backed up), never shown on the lock screen, home page and same server only.
  It is deleted when any page lands on sign-in, when the home load lands
  elsewhere, on unpair, when muster ends the pairing, on change server, and
  when it is more than 24 hours old. While it is up it swallows taps and shows
  a loading bar; it goes when the page has painted, on any page finish or
  main-frame error, and after 5 seconds whatever happens. The WebView's
  background is muster's dark background, not white. Leaving the app flushes
  the WebView's cookies to disk, so a sign-in survives the process being
  killed right after it.
- **Background refresh (0.1.2), paired phones only:** a WorkManager job every
  15 minutes (network connected, battery not low) reads `/api/device/config`
  with the device credential, so a pairing muster ended is dropped, and
  starts Chromium in that process, so an open while Android still keeps the
  process is a warm start. It is scheduled only once the phone is paired, and
  cancelled on unpair, on revocation and on change server; a run that finds
  the phone unpaired cancels it and starts no Chromium. A shown push starts
  Chromium too. Nothing renders or fetches muster pages in the background:
  the pages need the session cookie, which stays in the WebView. Its cost
  per run, measured on an emulator only, is in
  [docs/background-refresh-cost.md](docs/background-refresh-cost.md): about
  1.2 s from process start to done, 0.8 s of CPU, one 4 KB TLS exchange, no
  wakelock of its own and no alarm; the process peaks near 95 MB PSS and is
  left cached at about 56 MB plus an 8 MB renderer. Its battery cost on a
  real phone has not been measured.
- **Startup timing (debug builds):** `adb logcat -s MusterStartup` logs each
  cold-start phase in ms since the process started, and the page's
  Navigation and Paint Timing. Every build reports "Fully drawn" when the
  first muster page is on screen.

### 🔴 Uploaded files

muster serves uploaded bytes on its own origin, which is where the bridge
answers. muster #207 serves them sandboxed (`Content-Security-Policy: …
sandbox`, `nosniff`, and every non-image as a download), so the shell loads
`/tasks/{id}/attachments/…`, `/uploads/{id}/file` and `/f/{id}`
(`AttachmentGate.SANDBOX_CONFIRMED`, a compile-time switch, **on**). 🔴 That
holds only for a muster at #207 or later; with the switch off, the shell
refuses those routes everywhere, thumbnails included. Either way, a
notification tap or a launch path never opens one.

🔴 With the switch on, the app trusts muster's headers and does not check
them (no app-side check is robust: a page can `pushState` to any URL). A
muster older than #207, or a regression in its sandbox headers, would let an
uploaded HTML file's script run on muster's origin with everything the app
grants that origin: the bridge (a `pair` for someone else's account) and the
microphone, which the app grants muster's page without a prompt once it holds
`RECORD_AUDIO`.

A download (a response served as `Content-Disposition: attachment`) is handed
to the browser in a Custom Tab with its URL only: no cookie, no credential.
The browser may ask you to sign in to muster there.

### Not in Phase 1

Signed actions and inline reply (Phase 2), bubbles (Phase 3), widgets and live
status (Phase 4), the in-app updater and the update channel (Phase 5). No
release has been published.

## Facts that are fixed

- **Package name: `dev.zacx.muster`.** It is permanent: it is fixed at first
  install and at developer-verification registration. Never change it.
- **minSdk 30, targetSdk 36** (planned 37). compileSdk is 37, because current
  AndroidX releases require it. compileSdk does not change runtime behaviour.
- **No server address in this repo.** The app asks for the muster URL on
  first run.

## Layout

| module | what |
|---|---|
| `app` | the application: manifest, the launcher activity (server URL, shell, phone settings), the UnifiedPush service, wiring |
| `core/model` | pure Kotlin: payload v2 and its parser, the action-id table, the action policy, the attachment gate |
| `core/net` | pure Kotlin: the `/api/device/...` client (OkHttp, no redirects) |
| `core/auth` | pairing, the step-up key and its policy, the sealed credential store |
| `feature/shell` | the WebView: origin policy, navigation, bridge, insets, pull to refresh |
| `feature/push` | the push handler (decrypted-only), the push-host check, registration |
| `feature/notify` | channels and drawing notifications |
| `feature/stepup` | the invisible step-up activity (Phase 2; a stub) |

The rest of the design's layout (`core/data`, `bubble`, `widget`, `live`,
`update`, `build-logic`) arrives with the phase that needs it.

## Building

The toolchain comes from the Nix flake: JDK 17, plus an Android SDK built by
nixpkgs' `androidenv`. Nothing is installed globally.

```sh
nix develop                 # JDK 17 + SDK (platform 37.0, build-tools 36.0.0)
./gradlew test              # unit tests (JVM; notifications on Robolectric, API 30 and 34)
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # the UNSIGNED release APK
scripts/tests/sign-release-test.sh   # the signing script, with a throwaway key
```

`nix develop .#spikes` adds the emulator, x86_64 `google_apis` images for API
35, 36 and 37.0, and `ntfy-sh`. It is several GB. The flake accepts the
Android SDK licence on your behalf (`android_sdk.accept_license`); read it
first.

Without Nix, any JDK 17 and an Android SDK work. The Gradle wrapper pins
Gradle and checks the distribution's sha256.

CI (`.github/workflows/ci.yml`) runs the unit tests and builds a
**debug-signed** APK on every push and pull request. The debug key is the
throwaway one AGP generates on the runner. **CI uses the GitHub runner's
Android SDK and a Temurin JDK 17, not the flake**: the flake would download
the SDK on every run. compileSdk is pinned in Gradle and build-tools by the pinned AGP version, and AGP
installs them on the runner if they are missing. The workflow's actions are
pinned by commit SHA.

## Signing (read this before any release)

- **The release signing key is not in this repo, and never will be.** The
  operator generates it **offline** with `keytool`, keeps it offline, and
  keeps an encrypted backup in a second place. Losing it means no update can
  ever be installed over the app.
- **CI never holds the release key.** Releases are signed offline with
  `scripts/sign-release <unsigned.apk> <keystore> <alias> <cert-sha256>`:
  zipalign, `apksigner` with the v2 and v3 schemes, then it refuses the
  output unless both verify and the certificate's SHA-256 is the recorded
  one. `APK_SHA256=<hex>` also checks the unsigned APK first. The publishing
  script arrives with the update channel in Phase 5.
- `.gitignore` refuses every keystore and signing file (`*.jks`,
  `*.keystore`, `*.p12`, `*.pk8`, `keystore.properties`, …). Do not force-add
  one.
- Record the release certificate's SHA-256 fingerprint. Developer
  verification needs it. The release certificate (alias `muster-release`,
  RSA 4096) is:

  ```
  f660222a463633f6e65a87a1701d9ec862364ba4649e8e09d34b8171ad20f5e6
  ```

## Distribution

The app is sideloaded. Updates come from a self-hosted over-the-air channel
and an in-app updater (Phase 5). The signed APKs are also attached to GitHub
Releases, so [Obtainium](https://github.com/ImranR98/Obtainium) works too.
There is no Play Store build.

## Spikes

The Phase 0 spike app (the biometric step-up from a notification action, S3
and S4; lock-screen privacy, S11; WebView insets, S12; a local ntfy check, S9)
was removed in Phase 1. It is kept at commit
[`f652f32`](https://github.com/ZacxDev/muster-android/tree/f652f3256bbe487e9babb467e5f509a41ef54068/spikes),
the one muster's design doc cites. Results are in
[`docs/phase0-results.md`](docs/phase0-results.md).

## License

Not yet chosen.
