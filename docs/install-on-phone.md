# Installing the Phase 1 app on your phone

This is the sideload path for **Phase 1** (the shell, pairing and push), before
there is a release or an update channel. It installs the release build,
signed offline with the release key (README, "Signing").

> **What was tested, and what was not.** Steps 2–4 and 6–10 were run on an
> Android emulator (API 36, `google_apis`, headless) against a local muster
> at the commit the deployment runs, in both password and GoTrue sign-in
> modes, and a local ntfy 2.31.0 with the ntfy app 1.25.2, **with a debug-key
> build**. Step 5's release-key-signed APK was built and verified but has not
> been installed on any device, and nothing here has run on a real phone
> yet. The emulator's keystore is software-only, so the
> emulator always paired **read-only** (step 7). A real phone is expected to
> make a hardware-backed key; that is expected, not measured.

## Before you start: the server

muster must already have, and the app depends on each:

| Setting | Why | How to check |
|---|---|---|
| Web push on (`MUSTER_VAPID_PUBLIC_KEY`, `_PRIVATE_KEY`, `_SUBJECT`) | Without it muster sends no push, and the app shows "This muster server does not send push notifications." | The boot log says `web push: ENABLED`. |
| `MUSTER_PUSH_EXTRA_HOSTS` set to your ntfy host, exactly (no scheme, no port) | muster refuses a phone's endpoint on any other host. The app checks the same thing first and tells you what to fix. | The boot log says `MUSTER_PUSH_EXTRA_HOSTS adds 1 operator push host(s) … : <your ntfy host>`. |
| Your ntfy host reachable from muster on `https`, port 443 | muster sends a test push before it accepts a phone's registration. If that push fails, registration fails. | Registration in step 8 says "registered". A CDN bot rule that blocks Go's `User-Agent` shows up here (muster `docs/push.md`). |
| muster served on `https` with a certificate from a public CA | The app trusts system CAs only. A self-signed or user-installed CA is refused. | The muster page loads in step 6. |
| Optional: `MUSTER_ANDROID_ALLOW_PIN_KEYS=1` | Only for a phone with no fingerprint or face: lets it pair with a biometric-or-PIN key. Off, such a phone pairs read-only. | Boot log, `android pairing:`. |

## 1. Allow installing from your computer or file manager

Android asks the first time you open an APK: **Settings → Apps → Special app
access → Install unknown apps**, then allow the app you will open the APK with
(your file manager or browser). Turn it off again afterwards if you like.

## 2. Install ntfy from F-Droid

Install **ntfy** (`io.heckel.ntfy`) from [F-Droid](https://f-droid.org/packages/io.heckel.ntfy/).
The Play Store build also works as a UnifiedPush distributor, but the F-Droid
one needs no Google services. Tested with 1.25.2.

## 3. Point ntfy at your ntfy server

ntfy → **⋮ → Settings → Default server** → `https://<your ntfy host>` (the
value of `MUSTER_PUSH_EXTRA_HOSTS`) → **Save**.

🔴 **Do this before pairing.** The push endpoint's host is whatever ntfy's
default server is when muster registers, and with none set it is `ntfy.sh`,
which muster refuses. If you paired first, **unpair and pair again** (step
10) after setting it: **Register push again** keeps the endpoint ntfy already
made, on the old host.

## 4. Turn off battery optimization for ntfy

When ntfy opens, it shows "Battery optimization should be off…": tap **FIX
NOW** and allow it (or **Settings → Apps → ntfy → Battery → Unrestricted**).

🔴 **This is not optional.** Measured on the emulator: with ntfy's battery
optimization on, Android refused to start ntfy's listening service when the
muster app registered (`startForegroundService() not allowed`). muster
reported the phone as registered and "Test sent: 1 of 1 device(s) accepted
it", ntfy's server accepted the message, and **nothing reached the phone**,
until ntfy was opened by hand. With the optimization off, the same flow
delivered every notification with ntfy in the background.

## 5. Build, sign and install the muster APK

On the machine that holds the release key (in `nix develop`):

```sh
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release-unsigned.apk (unsigned)
KS_PASS=file:/path/to/keystore.pass scripts/sign-release \
    app/build/outputs/apk/release/app-release-unsigned.apk \
    /path/to/muster-release.p12 muster-release \
    f660222a463633f6e65a87a1701d9ec862364ba4649e8e09d34b8171ad20f5e6
# -> app/build/outputs/apk/release/app-release-signed.apk
sha256sum app/build/outputs/apk/release/app-release-signed.apk
```

`scripts/sign-release` exits 0 only if the signed APK verifies (v2 and v3)
and its certificate is the recorded release certificate (README, "Signing").
It also prints the signed APK's sha256.

Copy `app-release-signed.apk` to the phone and open it, or with USB
debugging:

```sh
sha256sum app/build/outputs/apk/release/app-release-signed.apk   # check it against the one sign-release printed
adb install app/build/outputs/apk/release/app-release-signed.apk
```

⚠ **A phone that already has a debug-key build must unpair, uninstall and
reinstall.** Android refuses an update signed with a different key: unpair
(step 10), uninstall the old app, then install this APK and pair again.

## 6. Enter the server URL and sign in

Open muster. It asks for your muster server's address: enter
`https://<your muster host>` and tap **Continue**. The app opens only that
origin; links elsewhere open in your browser.

Sign in on muster's own page, inside the app, as you would in a browser.

## 7. Pair: Account → Devices → Pair this phone

Menu (☰) → **Account** → **Devices** → **Pair this phone** → **Pair**.

- Pairing needs a sign-in from the **last five minutes**. If muster says so,
  tap **Sign out**, sign in again, and tap **Pair this phone** again.
- The app creates its step-up key and redeems the one-time code. Then, on
  Android 13 and later, it asks to allow notifications: allow them.
- The "Paired" dialog says what kind of phone this is:
  - "Its actions will ask for your biometric every time": a biometric key on
    secure hardware. This is what a phone with a fingerprint or face should
    show.
  - "This phone's keystore is software-only … It is paired read-only": what
    the emulator always showed. On a real phone it means the key was not
    hardware-backed, and the phone gets notifications but no actions.
  - Other "read-only" or "biometric or PIN" messages say why.

The Devices list then shows the phone (its name is the phone's manufacturer and model, e.g. "Google Pixel 8"). Push
registration finishes a few seconds after pairing; Phone settings (step 8)
shows when it is registered.

## 8. Check it works

1. **Phone settings**: long-press the muster launcher icon → **Phone
   settings**. Check:
   - **Paired**: yes (device N).
   - **Step-up key**: `biometric, every use (trusted_environment)` or
     `(strongbox)` is the hardware-backed key you want. The level in brackets
     is what the Keystore reported for the key (`KeyInfo`'s security level).
     `none: read-only` means no key, with the reason under it.
   - **Push**: registered. **Notifications**: allowed.
2. **A test notification**: Account → **Notifications** → **Send a test
   notification**. A "muster test notification" should appear within a few
   seconds, with ntfy in the background.
3. **A real one**: a task moving to ready for review shows "Task #N ready for
   review", and moving it on (complete) withdraws it. Tapping a notification
   opens muster at that task.

What a notification says is muster's own two lines, as a browser gets them.
⚠ For a task, the second line is the **first line of the task's text**
(measured: a task with body "probe body" showed "probe body"). Phase 1 shows
the same lines on the lock screen, so if task text is private, set **Settings
→ Notifications → Notifications on lock screen** to hide sensitive content
(Phase 0, S11: with "show all content", the lock screen shows it).

## 9. If push does not work

Phone settings says why, and **Register push again** retries.

| Phone settings says | Fix |
|---|---|
| "The ntfy app is using ntfy.sh …" (or another host) | Set ntfy's default server (step 3), then unpair and pair again (step 10). **Register push again** does not help here: ntfy hands back the endpoint it already made, on the old host. |
| "Install the ntfy app …" | Install ntfy (step 2). |
| "This muster server names no push host …" | Set `MUSTER_PUSH_EXTRA_HOSTS` on the server. |
| "muster refused this phone's push registration: …" | muster's test push to ntfy failed: check that muster reaches your ntfy host on 443. |
| registered, but nothing arrives | Turn off ntfy's battery optimization (step 4) and open ntfy once. |

## 10. Unpair or revoke

- **From the phone:** Phone settings → **Unpair this phone**. It removes the
  pairing on muster, drops the push registration and deletes the step-up key.
  It does not ask for confirmation.
- **From muster (a lost phone):** Account → Devices → **Remove** on the
  phone's row, from any signed-in browser. The credential stops working on
  the phone's next request; the app notices the next time it opens, forgets
  the pairing and unregisters push.
- **Change server:** only while unpaired, Phone settings → **Change server**
  forgets the server and its cookies.

## Known limits of Phase 1

- No action buttons on notifications, no inline reply (Phase 2).
- Uploaded files: images show in the app; every other file is handed to your
  browser as a download, which may ask you to sign in to muster there.
- No in-app updates.
