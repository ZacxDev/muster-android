# muster-android

The native Android app for [muster](https://github.com/ZacxDev/muster): a
Kotlin + Jetpack Compose shell that hosts muster's web UI in a WebView, plus
native modules for actionable notifications, bubbles, widgets and live status.
Push goes through [UnifiedPush](https://unifiedpush.org) with a self-hosted
[ntfy](https://ntfy.sh) distributor. No Google Play services, no FCM.

The design is muster's
[`docs/design/android.md`](https://github.com/ZacxDev/muster/blob/main/docs/design/android.md).
This repo follows it.

**Status: Phase 0.** There is a scaffold and the Phase 0 spikes. Results are
in [`docs/phase0-results.md`](docs/phase0-results.md). The app does nothing
useful yet.

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
| `app` | the application: manifest, network security config, backup rules |
| `core/model` | pure Kotlin: the payload action-id → route table |
| `feature/shell` | the WebView shell (the origin policy only, for now) |
| `feature/stepup` | the biometric step-up (a stub; the pattern is proven in `spikes/`) |
| `feature/notify` | notification channels (a stub) |
| `spikes` | throwaway Phase 0 spike app (`dev.zacx.muster.spikes`) and its scripts |

The rest of the design's module layout (`core/net`, `core/auth`, `core/data`,
`feature/push`, `bubble`, `widget`, `live`, `update`, `build-logic`) arrives
with the phase that needs it.

## Building

The toolchain comes from the Nix flake: JDK 17, plus an Android SDK built by
nixpkgs' `androidenv`. Nothing is installed globally.

```sh
nix develop                 # JDK 17 + SDK (platform 37.0, build-tools 36.0.0)
./gradlew test              # unit tests
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
```

`nix develop .#spikes` adds the emulator, x86_64 `google_apis` images for API
35, 36 and 37.0, and `ntfy-sh`. It is several GB. The flake accepts the
Android SDK licence on your behalf (`android_sdk.accept_license`); read it
first.

Without Nix, any JDK 17 and an Android SDK work. The Gradle wrapper pins
Gradle and checks the distribution's sha256.

CI (`.github/workflows/ci.yml`) runs the unit tests and builds a
**debug-signed** APK on every push and pull request. The debug key is the
throwaway one AGP generates on the runner.

## Signing (read this before any release)

- **The release signing key is not in this repo, and never will be.** The
  operator generates it **offline** with `keytool`, keeps it offline, and
  keeps an encrypted backup in a second place. Losing it means no update can
  ever be installed over the app.
- **CI never holds the release key.** Releases are signed offline with
  `apksigner` (zipalign first; v2 and v3 schemes). The scripts arrive in
  Phase 1 and 5.
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

`spikes/` holds the Phase 0 experiments: the biometric step-up from a
notification action (S3, S4), lock-screen privacy (S11), WebView safe-area
insets (S12), and a local ntfy check (S9). `spikes/scripts/` drives them on an
emulator over adb. See [`docs/phase0-results.md`](docs/phase0-results.md).

## License

Not yet chosen.
