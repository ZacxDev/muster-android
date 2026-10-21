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
- **The bridge:** `musterApp`, a `WebViewCompat.addWebMessageListener`
  restricted to the exact origin, main frame only. It understands the two
  messages muster sends: `pair` and `open-notification-settings`.
- **Pairing:** Account → Devices → **Pair this phone** hands the app a
  one-time code; the app asks you to confirm, makes the step-up key, and
  redeems the code at `POST /api/device/pair`.
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

### 🔴 Uploaded files are not rendered

muster serves uploaded bytes on its own origin, which is where the bridge
answers. Until muster's sandbox for them (muster #207) is confirmed deployed,
the shell refuses every route that serves them, `/tasks/{id}/attachments/…`,
`/uploads/{id}/file` and `/f/{id}`, as a page and as a subresource (so upload
thumbnails do not render either), and a notification never opens one
(`AttachmentGate.SANDBOX_CONFIRMED`, a compile-time switch, off).

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
| `feature/shell` | the WebView: origin policy, navigation, bridge, insets |
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
  verification needs it.

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
