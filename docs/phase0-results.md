# Phase 0 spike results

> The `spikes/` paths below refer to the spike app as it was at commit
> [`f652f32`](https://github.com/ZacxDev/muster-android/tree/f652f3256bbe487e9babb467e5f509a41ef54068/spikes);
> it was removed from the build in Phase 1.

Spikes for muster's
[`docs/design/android.md`](https://github.com/ZacxDev/muster/blob/main/docs/design/android.md)
(section "Phase 0 spikes"). S1 and S2 belong to Phase 7, and S6 to Phase 6;
none of the three ran here.

Each finding has a **verdict**, the **evidence**, and the **design
consequence**. "Measured" means it was run on an emulator or against a
server. "Docs" or "source" means it was read, not run, and the source and
date are given.

## Test bed and its limits

| item | value |
|---|---|
| Emulator | Android emulator 37.1.11, from nixpkgs `androidenv`, KVM, headless |
| API 36 image | `google_apis` x86_64, `BE2A.250530.026.F3`, security patch 2025-07-05, WebView 133.0.6943.137 |
| API 35 image | `google_apis` x86_64, `AE3A.240806.043`, security patch 2024-09-05, WebView 124.0.6367.219 |
| Device profile | `pixel_7` (1080×2400, density 2.625, gesture navigation) |
| Biometric | emulated fingerprint (`adb emu finger touch`), PIN 1111 |
| Spike app | `spikes/` (`dev.zacx.muster.spikes`, targetSdk 36), driven by `spikes/scripts/` |
| ntfy | `ntfy-sh` 2.31.0 from nixpkgs, local; plus the operator's deployed ntfy for S10 |

**What an emulator cannot show:**

- The emulator keystore is software: every key reported `securityLevel=0`
  (SOFTWARE). **S3 and S4 were measured on software keys only.** On a real
  phone the same code is **expected** to yield TEE (1) or StrongBox (2) keys
  with the same auth binding (per-use authentication, enrollment
  invalidation). That is **expected, not measured**: neither hardware
  backing nor how hardware keys behave was tested here.
- The images ship old WebViews (124 and 133), and a `google_apis` image
  cannot update WebView. A real phone runs the current Play-updated WebView.
- Lock-screen and biometric UI are AOSP/Pixel SystemUI. Other OEMs' skins are
  **not measured**.

## Verdicts

| id | question | verdict |
|---|---|---|
| S3 | BiometricPrompt + CryptoObject signing from a UI-less activity started by a notification action | **Works unlocked on API 35 and 36. FAILS from the lock screen as designed** (the prompt is cancelled behind the keyguard). Works from the lock screen once the activity calls `requestDismissKeyguard()` first. An activity **is** required: the receiver trampoline is blocked. |
| S4 | `AUTH_BIOMETRIC_STRONG \| AUTH_DEVICE_CREDENTIAL`, timeout 0, so a PIN-only phone can sign | **Works** on API 35 and 36, including on a phone with no biometric. **But** such a key is **not invalidated by a new biometric enrollment** (measured): weaker than the biometric-only key. |
| S7 | Developer verification details | **Docs.** Sideloading is not affected yet; the global rollout is 2027. After that, an unregistered app installs **and updates** only with the advanced flow on (7 days or indefinitely) or by ADB. Whether each update warns, and whether silent self-update survives, is **UNKNOWN** (undocumented). |
| S8 | Obtainium's unattended updates; F-Droid index v2 | **Source.** Obtainium installs silently only when it is the installer of record, on Android 12+, with targetSdk ≥ device SDK − 3 (else via Shizuku or root). fdroidserver still writes index v1 beside v2, so every client can read a self-hosted repo. |
| S9 | ntfy and VAPID; ntfy's FGS type; embedded-FCM and Firebase | **Measured + source.** ntfy does **not** enforce VAPID on `up*` (bogus VAPID → 200). ntfy-android uses FGS type **`specialUse`**. The embedded-FCM distributor needs **no Firebase project** (v3.1.0). **New:** an `up*` publish over the size limit (default 4K, configurable) is **accepted and silently truncated**. |
| S10 | Does muster reach ntfy by the exact name in the phone's endpoint? | **Measured, mostly.** From the cluster muster runs on, the operator's ntfy host resolves through public DNS to the CDN, and an anonymous `up*` POST returns 200 on `https`/443. The endpoint's host is **whatever server the ntfy app is set to**, and it defaults to ntfy.sh. Not measured: a real phone's registration. |
| S11 | Does the lock screen show only the public version of a `VISIBILITY_PRIVATE` notification? | **Only if the user's setting hides sensitive content.** With "show all notification content", the private text **is shown on the lock screen** (measured on API 35 and 36). |
| S12 | CSS `env(safe-area-inset-*)` in an edge-to-edge WebView | **No** on WebView 124 and 133: defined but **0 px** on every side while the native insets are 51.8 dp (top) and 24 dp (bottom). The page drew under the status bar. Current WebView versions are **not measured**. |

## S3: step-up from a notification action

**Setup.** `StepUpKeys` generates an EC P-256 key in AndroidKeyStore with
`setUserAuthenticationRequired(true)`,
`setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` and
`setInvalidatedByBiometricEnrollment(true)`. A notification carries a
**Sign** action whose PendingIntent is `PendingIntent.getActivity` on
`StepUpActivity`: a translucent, unexported `FragmentActivity` with no UI.
The activity builds a server-style challenge (a JSON object with nonce,
device, action, target and `text_sha256`) and runs BiometricPrompt with a
`CryptoObject(Signature)`. It then signs, verifies the signature against the
certificate's public key, logs, and calls `finish()`. Each run is one
`MUSTER_SPIKE` logcat line.

**Unlocked: works on API 35 and 36.**

```
S3 RESULT alias=s3-bio via=notification-action-activity variant=plain OK authType=2 signed=71B verified=true sdk=36 release=16 secure=true keyguardLocked=false
S3 RESULT alias=s3-bio via=notification-action-activity variant=plain OK authType=2 signed=71B verified=true sdk=35 release=15 secure=true keyguardLocked=false
```

`authType=2` is `AUTHENTICATION_RESULT_TYPE_BIOMETRIC`.

**From the lock screen: the design's flow fails.** The phone was locked with
the notification posted; the action was tapped on the lock screen; then a
finger was presented (`spikes/scripts/s3-locked.sh`). The system starts the
activity at once, **behind the keyguard**, and shows the bouncer. The prompt
is cancelled before the user can act on it: in five API 36 plain runs, the
logcat timestamps of the `START` and `ERROR` lines were 16–147 ms apart (the
API 35 runs logged only the result line). The finger then only unlocks the
phone, and nothing is signed:

```
S3 stepup-activity START via=notification-action-activity alias=s3-bio variant=plain sdk=36 ... keyguardLocked=true
S3 prompt shown alias=s3-bio allowed=15
S3 RESULT alias=s3-bio via=notification-action-activity variant=plain ERROR code=5 msg=Authentication canceled sdk=36 ... keyguardLocked=true
```

Variants, each run on API 35 and API 36, with identical results:

| variant | result |
|---|---|
| plain | **fails**: `ERROR code=5` (canceled) |
| plain + `Notification.Action.setAuthenticationRequired(true)` | **fails** the same way. The flag does not make the system wait for the unlock before starting the activity. |
| `KeyguardManager.requestDismissKeyguard()` first, prompt in `onDismissSucceeded` | **works**: bouncer → unlock → prompt → second touch → signed (`OK authType=2 verified=true`) |
| `setShowWhenLocked(true)` | **works**, also with two touches. It draws the app's activity over the keyguard, which the design does not need. |

Two authentications from the lock screen are inherent: the unlock, then the
per-use key's own authentication (timeout 0). One touch cannot do both.

**Is an activity required? Yes.** The control posts a **Sign** action to a
`BroadcastReceiver`, which calls `startActivity(StepUpActivity)`. On API 35
and 36 the platform blocks the start, and `startActivity` **throws nothing**:

```
S3 trampoline-receiver onReceive sdk=36 ...
E NotificationService: Indirect notification activity start (trampoline) from dev.zacx.muster.spikes blocked
S3 trampoline-receiver startActivity returned without exception
```

The API 35 result is identical. BiometricPrompt also needs a
`FragmentActivity` or `Fragment`, so a receiver cannot host it.

**Design consequences:**

1. The step-up activity must call `KeyguardManager.requestDismissKeyguard()`
   when `isKeyguardLocked`, and show BiometricPrompt only in
   `onDismissSucceeded`. If the dismiss is cancelled, it must finish and leave
   the notification as it was. The design's "A gated action, step by step"
   omits this, and without it every action tapped on the lock screen fails.
2. `setAuthenticationRequired(true)` is not a gate and not a fix. Keep it as
   defence in depth if you like, but the measured behaviour is "no
   difference" here.
3. Tell the user that from the lock screen a signed action costs two touches
   (unlock, then confirm).
4. A trampoline fails **silently**. Phase 2 needs an instrumented test that
   every action PendingIntent is `getActivity` straight to the step-up
   activity, because a receiver-based action would ship broken with no error.

## S4: device credential as the step-up

**Setup.** Key `s4-cred`, same as `s3-bio` but with
`setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG | AUTH_DEVICE_CREDENTIAL)`,
and BiometricPrompt with `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` (androidx
biometric 1.1.0; the `CryptoObject` + `DEVICE_CREDENTIAL` combination needs
API 30, which is the minSdk).

**Key generation (measured, API 35 and 36):**

| phone state | biometric-only key (`s3-bio`) | biometric-or-PIN key (`s4-cred`) |
|---|---|---|
| no lock screen | FAILED: "Secure lock screen must be enabled…" (s4) / "At least one biometric must be enrolled…" (s3) | FAILED |
| PIN, no biometric | FAILED: "At least one biometric must be enrolled to create keys requiring user authentication for every use" | **OK** `authType=3 timeout=0` |
| PIN + fingerprint | OK `authType=2 timeout=0 invalidatedByEnroll=true` | OK `authType=3 timeout=0` **`invalidatedByEnroll=false`** |

**Signing (measured):** on a PIN-only phone, from the notification action
(unlocked), the prompt offered the PIN pad, and the PIN signed with timeout 0:

```
S3 RESULT alias=s4-cred via=notification-action-activity variant=plain OK authType=1 signed=72B verified=true sdk=35 release=15 ...
S3 RESULT alias=s4-cred via=notification-action-activity variant=plain OK authType=1 signed=71B verified=true sdk=36 release=16 ...
```

`authType=1` is `AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL`. Negative
control: a wrong PIN (0000) gave no signature and the prompt stayed up. The
right PIN in the same prompt then signed (`OK authType=1`).

**Enrollment invalidation (measured, API 36).** After a second fingerprint
was enrolled:

```
S34 probe s3-bio initSign FAILED android.security.keystore.KeyPermanentlyInvalidatedException: Key permanently invalidated
S34 probe s4-cred initSign OK
```

`setInvalidatedByBiometricEnrollment(true)` was requested for both keys. The
platform reports `false` for the key that allows a device credential, and the
key survives a new enrollment.

**Design consequences** (for open question 3):

1. A PIN-capable step-up is **technically possible** on every phone with a
   secure lock screen.
2. It is **weaker** in two ways. Anyone who knows the PIN can sign. And
   anyone who knows the PIN can enrol their own finger without destroying the
   key, which defeats "Re-confirm this phone after an enrollment change".
3. Recommendation: keep `AUTH_BIOMETRIC_STRONG` alone as the default, as the
   design says. If PIN phones are allowed, register the key's auth type with
   muster at pairing (`biometric` or `biometric_or_credential`), show it on
   Account → Devices, and let muster refuse signed actions from
   credential-capable keys if the operator chooses. Do not let the app
   silently fall back from one to the other.
4. Since the emulator keystore is software, check `KeyInfo.securityLevel` on
   a real phone (or use key attestation, from "Recommended improvements") and
   refuse a SOFTWARE step-up key at pairing.

## S7: Android developer verification (docs)

Sources: the [FAQ](https://developer.android.com/developer-verification/guides/faq)
(updated 2026-09-30, read 2026-10-20);
[limited distribution](https://developer.android.com/developer-verification/guides/limited-distribution)
(updated 2026-09-30); the [verification
terms](https://developer.android.com/developer-verification/console/terms)
(effective 2025-11-11); the
[2026-03-30 blog](https://android-developers.googleblog.com/2026/03/android-developer-verification-rolling-out-to-all-developers.html).

| question | verdict | evidence |
|---|---|---|
| Browser and file-manager installs blocked in the enforcing countries? | **Not yet** | FAQ: "The September 30, 2026 deadline only applies to the specific participating stores. If you distribute your app through other stores, or if users sideload your app directly, these new verification requirements won't apply to your app yet." |
| The advanced flow | **Confirmed** | FAQ: developer mode, an anti-coaching check, a restart, then "a one-time, one-day wait", then "the option of enabling for 7 days or indefinitely. For safety, you'll still see a warning that the app is from an unverified developer, but you can just tap 'Install Anyway.'" |
| Updates of an unregistered app | **Confirmed: need the advanced flow or ADB** | FAQ: "Unregistered apps can only be installed or updated when the advanced flow is enabled or by using ADB so if the advanced flow is disabled updates to unregistered apps will fail." |
| Does every update warn; does silent self-update survive? | **UNKNOWN** | No Google page says. API 37 adds `PackageInstaller` developer-verification failure reasons, but their behaviour for updates was not read. |
| Limited-distribution device limit | **Confirmed: 20** | "up to 20 devices that end-users have explicitly authorized", via "QR codes or links, user consent on the device, and registration using the Android Developer Console". |
| Can authorised devices be swapped? | **UNKNOWN** | Not documented. |
| Is "no commercial intent" binding? | **Partly** | Terms §2.1: limited-distribution use "may be subject to different or additional terms … presented to You during the registration process". Those terms are visible only at registration. |
| Global rollout | **2027**, no exact date | FAQ: "before the global rollout begins in 2027". |
| ADB | **Exempt** | FAQ: "Apps installed using ADB won't require verification." |

**Design consequences:**

1. Nothing blocks any phase now: sideloading is unaffected until the global
   rollout in 2027.
2. "Stay unregistered" means every phone must enable the advanced flow **and
   keep it enabled**. With the 7-day option, updates fail once it lapses. The
   in-app updater must catch an install failure that looks like this, and
   tell the user, rather than retrying silently.
3. Silent OTA for an unregistered app after 2027 stays **UNVERIFIED**. Re-run
   this spike when an image with enforcement exists (API 37 has the APIs).
4. The open "20 devices, swappable?" question matters only to the
   limited-distribution path. Ask at registration.

## S8: Obtainium and F-Droid index v2 (source)

**Obtainium** (commit `9d3cdcc4`; spot-checked):

- The stock installer uses a PackageInstaller session with
  `setRequireUserAction(USER_ACTION_NOT_REQUIRED)` on Android 12+, and with
  `setRequestUpdateOwnership(true)` on 14+ (plugin `android_package_installer`
  `Installer.kt` L57-74).
- It attempts a silent install only when (`lib/installers/stock_installer.dart`
  L25-74):
  - it is not updating itself;
  - **Obtainium is the installer of record** ("If we did not install the app,
    silent install is not possible");
  - the device is on API ≥ 31;
  - the app's targetSdk ≥ the device SDK − 3.
- Shizuku/Dhizuku/Sui and root installers are always silent.
- Background checks run through WorkManager when "background updates" is on;
  otherwise the user gets a notification.
- `STATUS_PENDING_USER_ACTION` launches the system confirmation.

**F-Droid index v2** (fdroidserver `0c3166f2`, fdroidclient `8c1183fe`,
Neo Store `e74839c8`, Droid-ify `ff1453ed`):

- `fdroid update` still writes index v0, v1 **and** v2 (`index.py` L146-151);
  `entry.jar` is signed by `fdroid signindex`.
- The official client tries v2 (`entry.jar`) first and falls back to v1. It
  never downgrades once a repo served v2.
- Neo Store reads v2 by default. Droid-ify has both paths; which one its UI
  uses was not established.
- Which F-Droid client version added v2 is UNKNOWN (it dates from the
  1.16-alpha0 index overhaul, 2022-12-02).

**Design consequences:**

1. Obtainium is a full alternative channel **only if Obtainium did the first
   install**. A phone that installed from a browser and then adds the app to
   Obtainium gets prompted on every update. The README should say: install
   through Obtainium from the start if you want Obtainium.
2. Keep targetSdk current (within 3 of the newest Android). Otherwise
   Obtainium updates prompt on new phones. This matches the in-app updater's
   own targetSdk table.
3. A self-hosted F-Droid repo would work with every current client, but the
   design's verdict stands: heavier than needed for one app.

## S9: ntfy (measured locally + source)

**Local server** (`spikes/ntfy/s9-ntfy.sh`): ntfy 2.31.0, `auth-default-access:
deny-all`, `ntfy access everyone 'up*' write-only` (the rule UnifiedPush
needs), and a throwaway admin user to read messages back. The topic is 14
characters, like a UnifiedPush topic; bodies are random bytes sent with
`Content-Encoding: aes128gcm`.

```
1 up* publish, no Authorization:              200
2 up* publish, bogus 'vapid t=...,k=...':     200
3 up* publish, bogus 'WebPush <jwt>':         200
4 non-up topic publish (control, expect 403): 403
5 up* READ anonymously (expect 403):          403
6 up* publish 6000 B:                         200
--- stored messages (read as admin):
  encoding=base64 decoded_bytes=200  (x3)
  encoding=base64 decoded_bytes=4096 attachment=False
```

The first harness run returned 403 for every request, the controls included.
The access rule had been written before the server created its auth
database. Fixed by starting the server first. The numbers above are from the
fixed run.

| question | verdict | evidence |
|---|---|---|
| Does ntfy require VAPID on `up*`? | **No** (measured, local and deployed) | Rows 1–3 above. Source: `server/server_auth.go` (`5e11f4fc`), `supportedAuthHeader` accepts only `Basic`/`Bearer`, "neither are things like 'WebPush', or 'vapid' (see #629)", so a VAPID header is treated as anonymous. The deployed server gave 200 to a bogus VAPID header too (S10). |
| Which FGS type does the ntfy app use? | **`specialUse`** (source, spot-checked) | ntfy-android `a8427f56`, `AndroidManifest.xml` L111-115: `.service.SubscriberService` `android:foregroundServiceType="specialUse"` with a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`; permissions `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE` (L7-8). |
| Does the embedded-FCM distributor need a Firebase project? | **No** (source; not run) | codeberg `UnifiedPush/android-embedded_fcm_distributor` `d618c7ac` (v3.1.0): no Firebase dependency; it registers with the VAPID key as sender. `Module.md`: FCM accepts Web Push "out of the box, but they require a VAPID authorization. If your application supports VAPID, you have nothing special to do". It still needs Play services on the phone. |
| What happens to an `up*` message over the size limit? | **Accepted (200) and silently truncated to the limit** (measured at the default, 4096 bytes) | Row 6. The limit is ntfy's default `message-size-limit` (4K; `DefaultMessageSizeLimit = 4096`, `server/config.go` L72), which is **configurable**. The deployed server's value was **not measured**. |
| `Topic` / `Urgency` / `TTL` headers | **Ignored** (source only) | No server code reads them on publish. |

**Design consequences:**

1. The "only decrypted pushes are trusted" rule is **load-bearing**, not
   defence in depth: VAPID gives no sender authentication at the
   distributor. Anyone with the endpoint URL can publish, as the design
   already assumes. Keep `decrypted == false` → drop.
2. **New server rule:** muster must refuse to send an `android` push whose
   **encrypted** body exceeds 4096 bytes. Over its size limit, an ntfy server
   truncates the body and still returns 200; the phone then fails to decrypt
   it, and the message vanishes without an error. The aes128gcm overhead is
   an 86-byte header plus a 16-byte tag, so the plaintext budget is under
   about 3990 bytes. Payload v2's "ids, not content" fits easily. Add a test
   that the sender refuses an over-budget payload. The design's "4 KB budget
   … UNVERIFIED" becomes: **ntfy's default `message-size-limit` is 4K and is
   configurable; over it, a body is truncated silently (measured at the
   default). The deployed value was not measured.** A fixed 4096-byte cap in
   muster is the conservative choice, whatever an operator configures.
3. `specialUse` for ntfy means the ntfy app is not subject to Android 15's
   `dataSync` 6-hour cap. The battery figure is still ntfy's own claim, not
   measured.
4. The embedded-FCM fallback is possible without a Firebase project, but it
   needs Play services, which the goals exclude. It stays out.
5. The `Topic` header idea under "Recommended improvements" does nothing on
   ntfy. Drop it, or coalesce on muster's side (the design already does that
   for `status`).

## S10: muster → the operator's ntfy (measured)

Run 2026-10-20 against the operator's ntfy host (served on `https`, 443,
behind a CDN), from this development host and from a throwaway pod in the
namespace muster runs in.

| check | dev host | in cluster |
|---|---|---|
| DNS | public resolvers: two CDN A and two AAAA records. This host's local resolver returned nothing (a stale negative cache), so its probes pinned the public answer with `curl --resolve`. | cluster DNS: the same CDN addresses |
| `GET /v1/health` | 200 `{"healthy":true}` | 200 |
| anonymous `POST /up<12 random>` with `Content-Encoding: aes128gcm` | 200 | 200 |
| same, with a bogus `Authorization: vapid …` | 200 | — |
| anonymous POST to a non-`up` topic (control) | 403 | — |
| `Python-urllib` user agent (control) | 403 (CDN block) | — |
| `http://` | 301 to `https://` | — |

How the endpoint is built (ntfy-android `a8427f56`,
`up/BroadcastReceiver.kt` L78-80, spot-checked):
`baseUrl = repository.getDefaultBaseUrl() ?: context.getString(R.string.app_base_url)`,
then `"${baseUrl}/${topic}?up=1"`. The endpoint's host is **the default
server set in the ntfy app**, falling back to the public ntfy.sh when none is
set.

**Verdict:** muster's sender reaches the operator's ntfy by its public name on
`https`/443, through the same public path the phone uses. No split-horizon
name is involved. **Not measured:** a phone actually registering through the
ntfy app (that needs the app on a device, with its default server set).

**Design consequences:**

1. `MUSTER_PUSH_EXTRA_HOSTS` = the operator's ntfy host name, exactly. The
   https-only, default-port rule holds.
2. The CDN blocks some client user agents (urllib). muster's Go sender
   (`Go-http-client/1.1`) was accepted. Keep a test push at registration
   (server change 4), because a CDN rule change would otherwise fail silently.
3. The `http://` → 301 is harmless: muster only builds `https` endpoints, and
   with redirects refused (server change 21) a 301 would count as a failure,
   not be followed.
4. **Pairing must check the endpoint host.** If the ntfy app has no default
   server set, the endpoint is on ntfy.sh. muster refuses it (it is not in the
   allowlist), which is safe, but the user sees only "push registration
   failed". The app should compare the endpoint's host with
   `/api/device/config`'s expected push host and say "set ntfy's default
   server to …" before registering.

## S11: lock-screen privacy (measured)

**Setup** (`spikes/scripts/s11-lockscreen.sh`): with a PIN set and the phone
locked, the spike app posts two notifications on a high-importance channel
whose lock-screen visibility is left at its default:

- `111`: `VISIBILITY_PRIVATE`, private text `SECRETWHAT-111…`, and a public
  version with `PUBLICWHO agent-x` / `PUBLICWHERE repo/foo`;
- `112`: `VISIBILITY_PRIVATE`, private text `SECRETWHAT-112…`, no public
  version.

The phone is woken and the lock screen's view tree is dumped. This was run
under three values of the user setting `lock_screen_allow_private_notifications`
("Sensitive notifications" / "show all content") on API 35 and API 36, with
identical results:

| user setting | `SECRETWHAT-111` | `SECRETWHAT-112` | `PUBLICWHO` |
|---|---|---|---|
| unset (this image's default) | hidden | hidden | **visible** |
| 0: hide sensitive content | hidden | hidden | **visible** |
| 1: show all content | **VISIBLE** | **VISIBLE** | hidden |

**Verdict:** `VISIBILITY_PRIVATE` plus a public version keeps WHAT off the
lock screen **only while the user's setting hides sensitive content**. With
"show all content" the lock screen shows the full private notification. The
app does not decide this. Many phones' setup flows offer "show all content",
and what real OEM images default to is **not measured** here.

**Design consequences:**

1. The design's lock-screen rule ("the private version may show the question,
   draft or messages") is not enforceable through `VISIBILITY_PRIVATE`.
2. Keep WHAT **out of the notification itself** whenever the phone is locked.
   Post WHO/WHERE only. Add WHAT (the fetched text) only when
   `KeyguardManager.isKeyguardLocked == false` at post time, and on
   `ACTION_USER_PRESENT`. Downgrade back to WHO/WHERE on
   `ACTION_SCREEN_OFF`. This holds whatever the user's setting.
3. The held-reply notification ("shows the exact held text in its private
   version") must follow the same rule. Otherwise the held text appears on
   the lock screen for users with "show all content".
4. Keep the per-device opt-in for WHAT (open question 4), but make it mean
   "WHAT when unlocked", never "WHAT on the lock screen".
5. Launchers (notification dots and long-press previews) are expected to
   show content only on an unlocked phone. **Not measured.**

## S12: CSS safe-area insets in an edge-to-edge WebView (measured)

**Setup** (`InsetsWebViewActivity` + `assets/insets.html`): an edge-to-edge
`ComponentActivity` (`enableEdgeToEdge()`, targetSdk 36) hosts a full-window
WebView with **no padding from the shell**. The insets are passed through
untouched. The page sets `viewport-fit=cover` (or `auto`) and reads
`env(safe-area-inset-*, 777px)`; 777 px is a sentinel that would show only if
the variable were undefined. The activity logs the native insets the WebView
was handed.

```
S12 webview package=com.google.android.webview version=133.0.6943.137 sdk=36 ...
S12 native fit=cover density=2.625 systemBars[l=0.0 t=51.809525 r=0.0 b=24.0] cutout[l=0.0 t=51.809525 r=0.0 b=0.0] view=1080x2400px
S12 page fit=cover when=3s padding[t=0px b=0px l=0px r=0px] vars[t=0px b=0px] inner=412x915 dpr=2.625 ua=Chrome/133.0.6943.137
```

The same holds for `fit=auto`, and for WebView 124 on API 35. The screenshot
shows the page's first line drawn under the status bar.

**Verdict:** **No.** On WebView 124 and 133, `env(safe-area-inset-*)` is
defined but 0 px on every side, while the real insets are 51.8 dp at the top
(status bar and cutout) and 24 dp at the bottom (gesture bar). Whether a
current WebView reports real values was **not measured**: these images cannot
update WebView.

**Design consequences:**

1. **The shell pads**, not the page. Apply the system-bar, display-cutout and
   IME insets as padding on the WebView's container (or size the WebView to
   the safe area). The container must **consume** the insets it pads for
   (return `WindowInsetsCompat.CONSUMED`, or the insets minus what it
   applied), so the WebView is not handed them again and nothing is padded
   twice. **UNVERIFIED:** that a newer WebView, which might report real
   `env()` values, then reports 0 inside the padded, inset-consumed view.
   Check it in the Phase 1 re-test below.
2. If the page later wants to draw behind the bars (for colour), the bridge
   can send the inset values and the page can set CSS custom properties from
   them. That is a fifth bridge message, and is not needed now.
3. Re-check on a real phone with the current WebView during Phase 1. It is
   cheap: the spike activity and page are in `spikes/`.

## Summary of changes the spikes imply for `docs/design/android.md`

1. **Step-up activity (S3):** when locked, call
   `requestDismissKeyguard()` and show the prompt only after
   `onDismissSucceeded`. The lock-screen path costs two touches.
   `setAuthenticationRequired(true)` measured as no help. Add an
   instrumented test that every action is `getActivity` straight to the
   step-up activity (trampolines fail silently).
2. **Open question 3 (S4):** a PIN-capable key works, but it survives a new
   biometric enrollment and is PIN-guessable. If it is allowed at all, record
   the key's auth type at pairing, show it on Account → Devices, and let
   muster refuse it by policy. Refuse SOFTWARE-level keys at pairing.
3. **Lock screen (S11):** `VISIBILITY_PRIVATE` does not keep WHAT off the
   lock screen when the user chose "show all content". Post WHAT only while
   unlocked, and downgrade on screen-off. This applies to held replies too.
   Open question 4 becomes "WHAT when unlocked".
4. **Push size (S9):** ntfy truncates `up*` bodies over its
   `message-size-limit` (default 4K, configurable; the deployed value was not
   measured) and still returns 200. muster must cap the encrypted body at
   4096 bytes and refuse larger sends. Replace the "4 KB … UNVERIFIED" line.
5. **VAPID (S9):** measured as not enforced by ntfy. The decrypted-only rule
   is the only sender check, so mark it load-bearing. Drop the `Topic`
   header improvement (ntfy ignores it). The embedded-FCM fallback needs no
   Firebase project, but it does need Play services. It stays out.
6. **Push host (S10):** `MUSTER_PUSH_EXTRA_HOSTS` = the operator's ntfy host
   (public name, reached through the CDN from the cluster; no split horizon).
   At pairing the app checks the endpoint's host against the expected push
   host, because the ntfy app's default server decides it and falls back to
   ntfy.sh.
7. **Insets (S12):** the shell pads for insets, and its container consumes
   them so nothing is padded twice. `env()` reads 0 on WebView 124 and 133.
   Replace the UNVERIFIED note with that, and keep current WebView's
   behaviour as UNVERIFIED until Phase 1 re-checks it.
8. **Distribution (S7, S8):** unregistered updates after 2027 need the
   advanced flow kept on (the 7-day option lapses). The in-app updater must
   surface that failure. Obtainium is silent only if it did the first
   install, so say so. Silent OTA after enforcement stays UNVERIFIED; re-run
   S7 on an enforcing image.
9. **compileSdk:** current AndroidX (core-ktx 1.19.1) requires compileSdk 37.
   targetSdk stays 36. The "Security" section's SDK line can say so.

## What did not run

- **Real hardware:** no physical phone. Hardware-backed keys, OEM lock
  screens and current WebView are unmeasured.
- **S10 phone side:** no ntfy app was installed on a device, so a real
  registration's endpoint was not observed. The construction was read in
  source.
- **S7 update behaviour after enforcement:** no image with enforcement exists
  to test against.
- **S11's launcher part:** only the lock screen was measured. What launchers
  show (notification dots, long-press previews) was not tested.
- **The deployed ntfy's `message-size-limit`** was not measured (S9 measured
  the default on a local server).
- **S8 and the embedded-FCM part of S9** were read from source, not run.
- **S1, S2 (Phase 7) and S6 (Phase 6)** were out of scope.

## Reproducing

```sh
nix develop .#spikes
./gradlew :spikes:assembleDebug
# create an AVD from system-images;android-36;google_apis;x86_64 (or 35), boot it, then:
adb install -r spikes/build/outputs/apk/debug/spikes-debug.apk
adb shell pm grant dev.zacx.muster.spikes android.permission.POST_NOTIFICATIONS
adb shell locksettings set-pin 1111          # then enrol an emulated fingerprint in Settings
adb shell am start -n dev.zacx.muster.spikes/.SpikeActivity --es cmd keygen --es alias s3-bio
spikes/scripts/s3-locked.sh s3-bio false dismiss
spikes/scripts/s11-lockscreen.sh 1
adb shell am start -n dev.zacx.muster.spikes/.InsetsWebViewActivity --es fit cover
spikes/ntfy/s9-ntfy.sh "$(mktemp -d)"
adb logcat -s MUSTER_SPIKE
```
