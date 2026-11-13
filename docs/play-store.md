# Publishing on Google Play

The app is published on Google Play as well as sideloaded from GitHub
Releases. This supersedes "sideload only, no Play Store build" in muster's
[`docs/design/android.md`](https://github.com/ZacxDev/muster/blob/main/docs/design/android.md)
(the operator's decision). Nothing else about the app changes for Play:
push stays UnifiedPush through ntfy, with no FCM and no Google Play services.

Related: the [privacy policy](privacy-policy.md) and the
[store listing draft](play-listing.md).

## The decisions this rests on

- **A personal developer account.** A new personal account must run a closed
  test with at least 12 testers, opted in continuously for at least 14 days,
  before it can apply for production
  ([Play Console Help 14151465](https://support.google.com/googleplay/android-developer/answer/14151465)).
- **Play App Signing with the EXISTING release key.** The operator uploads the
  release key (alias `muster-release`, RSA 4096) as the app signing key, so the
  APKs Play delivers carry the same certificate as the sideloaded ones:
  ```
  f660222a463633f6e65a87a1701d9ec862364ba4649e8e09d34b8171ad20f5e6
  ```
  🔴 Therefore nothing a muster server trusts changes: the WebAuthn origin
  stays `android:apk-key-hash:9mAiKkY2M_bmWoehcB2eyGI2S6Rkno4J00uBca0g9eY`, and
  `/.well-known/assetlinks.json` needs no new fingerprint. A user can also move
  between the Play build and a GitHub APK of the same or a higher versionCode
  without uninstalling, because both are signed by one key.
- **The upload key is that same key.** Bundles are signed with it before upload
  (`scripts/sign-bundle`). Google recommends a separate upload key
  ([Play Console Help 9842756](https://support.google.com/googleplay/android-developer/answer/9842756));
  the cost of one key is that a leak of it is a leak of the app signing key
  too, as it already is for the sideloaded APKs. A separate upload key can be
  registered later in Play Console (App integrity → App signing → request an
  upload key reset) without touching the app signing key or any server.

## One time: enrol Play App Signing (PEPK)

Do this on the machine that holds the release key, when Play Console first
asks how the app is signed (creating the first release, or App integrity →
App signing).

1. In Play Console choose **Export and upload a key from Java keystore**.
2. Download, from that page, **pepk.jar** and **encryption_public_key.pem**
   into a private directory **outside any git work tree**:
   ```sh
   install -d -m 700 ~/.local/share/muster-android/play
   # save both downloads there
   ```
3. Pin the jar. If the Console page shows a checksum for pepk.jar, use it;
   otherwise record the sha256 of the jar you downloaded
   (`sha256sum "$P/pepk.jar"`) and keep it with the release notes. The
   script checks the jar against `PEPK_SHA256` only when it is set, so set
   it. Then run the export (in `nix develop`, from this repo):
   ```sh
   R=~/.local/share/muster-android/release
   P=~/.local/share/muster-android/play
   PEPK_JAR=$P/pepk.jar PEPK_SHA256=<sha256 of pepk.jar> \
   KS_PASS=file:$R/keystore.pass \
     scripts/play-export-key "$R/muster-release.p12" muster-release \
       "$P/encryption_public_key.pem" \
       f660222a463633f6e65a87a1701d9ec862364ba4649e8e09d34b8171ad20f5e6 \
       "$P/muster-release-pepk.zip"
   ```
   It runs pepk with `--include-cert --rsa-aes-encryption`, then refuses the
   zip unless it holds `encryptedPrivateKey` and a `certificate.pem` whose
   SHA-256 is the release certificate's.
4. Upload `muster-release-pepk.zip` on the same Console page, and save.
5. Check: **App integrity → App signing → App signing key certificate →
   SHA-256** must read `F6:60:22:2A:…:F5:E6` (the fingerprint above). Then
   delete the zip and pepk.jar:
   ```sh
   rm -f ~/.local/share/muster-android/play/muster-release-pepk.zip \
         ~/.local/share/muster-android/play/pepk.jar
   ```

🔴 **The password, and why there is a script.** pepk reads the keystore
password only from `--keystore-pass=<password>` on its command line or from a
terminal prompt. Measured with a throwaway key: with stdin redirected from the
password file it crashes (`System.console()` is null), and run under a
pseudo-terminal (`script`) the terminal echoes the piped password into the
output. `scripts/play-export-key` puts the whole launch (`-jar`, the jar,
`--keystore-pass`) in a java `@argfile`, mode 0600 in a private temp dir
deleted on exit, so the process's argv is `java @<file> …` and never the
password. Without `KS_PASS`, pepk prompts on the terminal instead (keystore,
then key; type the keystore password both times).

The equivalent without the script, typing the password at pepk's prompts:
```sh
java -jar "$P/pepk.jar" --keystore="$R/muster-release.p12" --alias=muster-release \
  --output="$P/muster-release-pepk.zip" --include-cert \
  --rsa-aes-encryption --encryption-key-path="$P/encryption_public_key.pem"
```
Never pass `--keystore-pass=` on a command line by hand: it lands in shell
history and in every process listing. If the Console shows a command with
different flags, follow the Console and tell the script's maintainer.

`scripts/tests/play-export-key-test.sh` runs the script against a throwaway
keystore and a throwaway encryption key, and checks that the password appears
in no process's argv (with a control that the check does see a process
carrying it) and not in the output. It needs `PEPK_JAR` (pepk.jar is Google's
download), so it is not in CI: run it once, with the Console's pepk.jar,
before the real export.

## Each release: build and sign the App Bundle

Play takes an Android App Bundle (`.aab`), not an APK. In `nix develop`:

```sh
./gradlew bundleRelease
# -> app/build/outputs/bundle/release/app-release.aab (unsigned)

R=~/.local/share/muster-android/release
KS_PASS=file:$R/keystore.pass scripts/sign-bundle \
  app/build/outputs/bundle/release/app-release.aab \
  "$R/muster-release.p12" muster-release \
  f660222a463633f6e65a87a1701d9ec862364ba4649e8e09d34b8171ad20f5e6 \
  ~/muster-<version>-<versionCode>.aab

# re-check any bundle before uploading it:
scripts/sign-bundle --verify ~/muster-<version>-<versionCode>.aab \
  f660222a463633f6e65a87a1701d9ec862364ba4649e8e09d34b8171ad20f5e6
```

`scripts/sign-bundle` signs with `jarsigner` (an App Bundle is signed with the
JAR scheme; Play signs the APKs it generates with the app signing key). It
refuses its output unless `jarsigner` verifies it, **every** entry is signed
(counted from `jarsigner -verify -verbose` against the bundle's entry list,
because `jarsigner -verify` alone exits 0 and prints `jar verified.` for a
bundle with an unsigned entry added after signing), and every signer
certificate's SHA-256 is the recorded one. The password goes to jarsigner as
`-storepass:file` (or `:env`), never in argv. `AAB_SHA256=<hex>` also checks
the unsigned input.

**Why a script and not a Gradle `signingConfig`:** the release key then never
enters the Gradle build (no keystore path or password in Gradle properties,
no key material near the Gradle daemon or its build cache), CI stays a build
that cannot sign, and it is the same offline step as `scripts/sign-release`
for the APK, with the same password spellings and the same refusal on a
wrong certificate. `scripts/tests/sign-bundle-test.sh` tests it with a
throwaway key, and CI runs it.

The GitHub release APK comes from the same commit, signed with
`scripts/sign-release` as before (README, "Signing").

## versionCode: one sequence for both channels

Play needs every upload's versionCode to be higher than every versionCode
uploaded before, on any track; a number, once uploaded, can never be reused.
Android refuses to install a lower versionCode over a higher one.

- **One sequence, shared by Play and GitHub Releases.** Every release bumps
  `versionCode` by one in `app/build.gradle.kts`, and the Play bundle and the
  GitHub APK of a release are built from the same commit, with the same
  versionCode. Then a user can switch channels without an uninstall.
- The last release is v0.4.0, versionCode **10**. The first Play upload is
  either v0.4.0 itself (10, which Play has never seen) or the next release,
  **11**. Every later release is higher.
- Never upload a Play-only build under a number a GitHub release already
  uses, or the reverse: two different builds with one versionCode cannot
  replace each other.
- A rejected or abandoned Play upload still uses its number. Bump and
  rebuild; do not try to reuse it.

## 🔴 No self-update in the Play build

Play's Device and Network Abuse policy forbids an app distributed on Play
from updating itself by any means other than Play, and
`REQUEST_INSTALL_PACKAGES` is a restricted permission. The in-app updater
planned for Phase 5 must therefore never ship in the Play bundle: give it a
build flavor (or a build-time switch) that leaves the updater and that
permission out of what `bundleRelease` produces.

## Permissions: answers for Play Console

What each permission in the release manifest is for, for the Console's
questions and a reviewer's.

| Permission | From | Why | Play-restricted? |
|---|---|---|---|
| `INTERNET` | app | Talking to the muster servers the user adds. | No. |
| `POST_NOTIFICATIONS` | app, `feature/notify` | muster's push notifications; asked for at run time right after pairing. | No (runtime). |
| `RECORD_AUDIO` | app | Dictation on muster's page (`getUserMedia`): granted to the muster origin only, audio only; asked for the first time the page wants it. | No, but it is a sensitive runtime permission: the listing and privacy policy must say what it is for (they do). |
| `MODIFY_AUDIO_SETTINGS` | app | WebView's audio capture needs it (without it `getUserMedia` fails, measured on API 36). Install-time. | No. |
| `USE_BIOMETRIC`, `USE_FINGERPRINT` | `androidx.biometric` | The step-up key's BiometricPrompt. | No. |
| `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE` | WorkManager | The 15-minute background refresh (network-constrained, rescheduled after reboot). | No. |
| `dev.zacx.muster.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | `androidx.core` | Signature permission AndroidX uses for its own non-exported dynamic receivers. | No. |

No permission is on Play's restricted list. The app uses no foreground
service type (`FOREGROUND_SERVICE` comes from WorkManager): if the Console
asks for a foreground service declaration, the answer is "none used".

## In Play Console: the operator's list

1. Register the personal developer account (identity verification included).
2. Create the app: name `muster`, default language, **App**, **Free**.
3. App signing: the PEPK steps above; check the app signing certificate's
   SHA-256 is `F6:60:22:…:F5:E6`.
4. **App content** (Policy → App content), from [play-listing.md](play-listing.md):
   privacy policy URL, app access (reviewer instructions with a demo server
   and account), ads (**No**), content rating questionnaire, target audience
   (**18 and over**), data safety, government app (**No**), financial
   features (**None**), health (**No**).
5. **Store listing**: title, short and full description, app icon 512×512
   32-bit PNG, feature graphic 1024×500, at least 2 phone screenshots
   ([play-listing.md](play-listing.md) lists which).
6. **Closed testing**: create a track, add at least 12 testers (an email list
   or a Google Group), upload the signed bundle, roll out, and keep 12
   testers opted in for 14 days in a row. Testers need the ntfy app from
   F-Droid or Play and a muster server account; give them
   [install-on-phone.md](install-on-phone.md)'s server and ntfy steps.
7. After 14 days: **apply for production access** (closed test details,
   app information, production readiness).
8. Keep the demo server and the reviewer account alive for as long as the
   app is on Play: a review can come with any update.
