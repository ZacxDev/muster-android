# muster for Android: privacy policy

Effective: 2026-11-13. Applies to the muster Android app (package
`dev.zacx.muster`), from Google Play and from GitHub Releases.

## In short

- **The developer of this app collects nothing.** The app has no server of
  its own, no account with its developer, no analytics, no advertising, no
  crash reporting and no tracking SDKs. Nothing about you or your phone is
  ever sent to the app's developer.
- **The app talks only to the muster servers you add**, and receives push
  notifications through the ntfy app you install and the ntfy server you
  choose. A muster server is run by whoever operates it (you, or your
  organisation). What that server keeps, and for how long, is up to its
  operator and their privacy policy, not this one.

## What the app sends, and where

### To each muster server you add

You type a server's address into the app. The app then opens only that
server's web pages, over HTTPS, and sends that server:

- **What you do on its pages.** muster's interface is a web page served by
  your muster server and shown inside the app. What you type or choose there
  (your sign-in details, answers, messages, tasks) goes to that server, as it
  would in a browser. The server's sign-in cookies are kept by the app (below).
  If a server says its sign-in passes through an identity provider, the app
  opens that provider's sign-in pages too, for that server only.
- **When you pair the phone** (Account → Devices → Pair this phone): the
  one-time pairing code, a device name made from the phone's manufacturer and
  model (for example "Google Pixel 8"), the app's version, and the public half
  of a "step-up" signing key the app creates on the phone. The server answers
  with a device credential, kept on the phone (below).
- **For push notifications:** the push address the ntfy app made for this
  app, and the public keys the server needs to encrypt notifications to this
  phone.
- **When you act on a notification** (for example, answer an agent's question
  from its buttons): the action, the answer, and a signature made with the
  step-up key after you confirm with your fingerprint, face or PIN.
- **In the background, while paired:** about every 15 minutes, when the phone
  has a network connection and the battery is not low, the app asks the
  server whether the pairing is still valid (sending the device credential).
  The app also reads the server's public name and logo (when you add the
  server, when the app starts and in that background check), unless the
  server's last answer is still fresh.
- **Audio, only when you dictate.** See "Microphone" below.
- **Files, only the ones you pick.** When a muster page asks for a file, the
  system file picker opens; only the files you choose are given to the page,
  which uploads them to that server.

Every server is separate: what you do on one server is never sent to another,
and each server's sign-in, keys and notifications are kept apart.

### Push notifications: the ntfy app and your ntfy server

Notifications reach the phone through [UnifiedPush](https://unifiedpush.org)
with the [ntfy](https://ntfy.sh) app as the distributor. Your muster server
sends each notification, **encrypted to this phone**, to the ntfy server set in
the ntfy app (chosen by you or your server's operator); the ntfy app hands it
to this app, which decrypts it on the phone. The ntfy server and the ntfy app
see the encrypted message and when it was sent, not its content. This app
drops any message it cannot decrypt. The ntfy app and the ntfy server have
their own privacy terms.

### Links and downloads

A link to any other website opens in your browser (a Custom Tab), not in this
app; the app hands the browser the link only. A file the server offers as a
download is handed to the browser the same way, without the app's sign-in.

### Android's own components

The app shows pages with Android System WebView, uses the phone's biometric
prompt and screen lock, and uses the phone's passkey provider when a muster
page asks for a passkey. These are parts of Android and work under their own
settings and privacy terms (for example, WebView's Safe Browsing checks, which
Android turns on by default). Your fingerprint or face never leaves the phone
and is never seen by this app; the app only learns that you confirmed.

## Microphone

The app asks for the microphone (RECORD_AUDIO) only the first time a muster
page asks to record, for dictation. Audio is given only to a page of the muster
server you are using, and only audio (no camera). The page sends what you
dictate to that muster server (for example, to turn it into text). The app
itself does not record in the background, does not keep any recording, and
sends audio nowhere else. You can turn the permission off at any time in
Android's settings; the rest of the app keeps working.

## Notifications

The app asks to show notifications right after you pair the phone. A
notification shows the text your muster server sent: for example, who is
waiting and on what, or an agent's question and its answers. Notifications
show this text on the lock screen too (muster's design); Android's lock-screen
notification settings can hide it. You can turn notifications off, per server
and per kind, in Android's settings.

## What is stored on the phone

All of it is in the app's private storage, is **excluded from Android backup
and device-to-device transfer**, and is deleted when you uninstall the app.

- **Keys in the Android Keystore:** a key that encrypts the app's stored data
  (AES-256-GCM), and one step-up signing key per paired server (EC P-256),
  which needs your biometric (or PIN) for every use. Keystore keys cannot be
  copied off the phone.
- **The server list, encrypted with that key:** each server's address, its
  device credential, its pairing and push state.
- **Each server's web data:** cookies (your sign-in), and the page storage and
  cache a browser keeps, separately per server.
- **A picture of each server's home page** (so the app opens instantly): kept
  at most 24 hours, deleted when you are signed out, unpair or remove the
  server, and never shown on the lock screen.
- **Each server's public name and logo.**
- **Whether the app has already asked for the microphone.**

"Remove this server" deletes that server's pairing (on the server too, where
it can reach it), keys, sign-in, notifications and stored data. Uninstalling
deletes everything.

## What the app does not do

No analytics, advertising, crash reporting or tracking. No location, contacts,
camera, call log, SMS or list of installed apps. No advertising ID. The only
device identifiers the app sends are the ones listed above (the device name,
the step-up public key and the push address), and only to the muster server
you add. No data sold or shared with anyone by the developer: the developer
receives none.

## Your data on a muster server

Your account, tasks, messages, dictation and uploads live on the muster server
you use. To see, export or delete them, ask that server's operator (if you run
it, you hold them). The app cannot delete data a server holds, except a
pairing, which "Unpair" and "Remove this server" end.

## Security

The app connects over HTTPS only, trusts only the system's certificate
authorities, and never sends one server's credentials to another.

## Children

The app is a tool for operating a muster server and is not directed at
children.

## Changes

Changes to this policy are published at this address, with a new effective
date. The history is the file's history in the
[muster-android repository](https://github.com/ZacxDev/muster-android).

## Contact

Questions about this policy: `<contact email>`.
