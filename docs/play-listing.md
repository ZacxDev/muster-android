# Google Play listing: draft

The text and the Play Console answers for the muster app. Every statement
about data comes from the [privacy policy](privacy-policy.md), which is
derived from the code. Placeholders in `<angle brackets>` are for the
operator. The procedure is [play-store.md](play-store.md).

## Store listing

**App name** (30 characters at most):

```
muster
```

(Longer option, 22 characters: `muster: agents & tasks`.)

**Short description** (80 characters at most; this one is 79):

```
Your muster server on your phone: tasks, agent questions and push notifications
```

**Full description** (4000 characters at most; this one is about 1,800). Play shows line breaks as
typed, so each paragraph is one line:

```
muster for Android is the phone app for muster, a self-hosted task board and control room for coding agents. It shows your muster server's own interface, and adds what only a phone can do well: notifications you can act on, and a biometric check before anything is sent.

You need a muster server. The app does not include one and does not create an account: you enter your server's address, sign in on its page, and pair the phone from Account → Devices.

What it does
• Your board, tasks and agent sessions, from your own muster server.
• Push notifications through UnifiedPush and the ntfy app. No Google Play services, no Firebase: notifications are encrypted by your server and decrypted only on your phone.
• Answer an agent's question straight from the notification. Each answer is signed with a key kept in your phone's secure hardware, after your fingerprint or face (or, if your server allows it, your PIN) confirms it.
• Several muster servers at once, each with its own sign-in, pairing and notifications, and a switcher to move between them.
• Dictation: speak into muster's text fields. The microphone is used only when you start dictating on a muster page.
• Passkey sign-in, where your server supports it.

Privacy
The app's developer collects nothing: no analytics, no ads, no tracking. The app talks only to the muster servers you add (and the sign-in pages they name); notifications arrive through the ntfy app and the ntfy server you choose. Your data lives on your muster server. Stored credentials and keys stay on the phone, encrypted, and are never backed up.

Requirements
• A muster server reachable over HTTPS, with web push turned on.
• The ntfy app (from Google Play or F-Droid) for notifications, pointed at your ntfy server.
• Android 11 or newer.

Source code: https://github.com/ZacxDev/muster-android
```

**Category:** Productivity. (Alternative: Tools.)
**Tags** (Console suggests; pick up to 5): Productivity, Task management,
Developer tools.
**Contact details:** email `<contact email>` (required, shown publicly);
website `https://github.com/ZacxDev/muster-android` (optional).
**Privacy policy URL:**
`https://github.com/ZacxDev/muster-android/blob/main/docs/privacy-policy.md`

## Graphics

| Asset | Requirement | What |
|---|---|---|
| App icon | 512×512, 32-bit PNG with alpha, ≤1 MB | The launcher icon (from muster's PWA icon) at 512 px. |
| Feature graphic | 1024×500, JPEG or 24-bit PNG, no alpha | The icon and "muster" on muster's dark background. |
| Phone screenshots | at least 2 (up to 8), JPEG or 24-bit PNG, no alpha; 9:16 portrait, at least 1080×1920 for the recommended formats | The list below. |

Requirements from [Play Console Help 9866151](https://support.google.com/googleplay/android-developer/answer/9866151).

**Phone screenshots**, taken from a demo server with made-up content (no real
names, hosts, repositories or tasks). Recommended order:

1. **The board** in the shell, the server switcher bar at the top (the first
   screenshot sells the app).
2. **An agent question as a notification**, expanded, with its answer
   buttons and Dismiss.
3. **The biometric confirmation** for an answer (the BiometricPrompt naming
   the exact answer). Android blocks screenshots of some system prompts; if
   so, use a photo-free mock of that step, or skip it.
4. **An agent session / task page** in the shell.
5. **The server switcher** open, with two servers (names and logos).
6. **Phone settings** for a server: pairing, step-up key, push "registered".
7. *(optional)* **First run:** the "muster server address" screen.

## App access (reviewer instructions)

Policy → App content → App access → "All or some functionality is
restricted". Play's reviewers need a working server and account; keep both
alive while the app is on Play.

```
The app is a client for a self-hosted muster server. It needs a server
address and an account; we provide a demo server.

1. Open the app. On "muster server address", enter:
     <demo server URL>
   and tap Continue.
2. Sign in on the server's page:
     Email:    <reviewer email>
     Password: <reviewer password>
3. You now see the muster board. Open a task and an agent session to see
   the main screens.
4. Pairing (optional, for the notification features): open Account →
   Devices → "Pair this phone" and confirm. The phone pairs read-only if it
   has no fingerprint or face enrolled.
5. Notifications need the ntfy app (Google Play: "ntfy", package
   io.heckel.ntfy). Before pairing, in ntfy: Settings → Default server →
     <demo ntfy server URL>
   then turn off battery optimisation for ntfy when it asks. After pairing,
   the app shows "Push: registered" in its phone settings (long-press the
   launcher icon) and the server sends a test notification.
6. To see a question notification, <how the demo server produces one, e.g.
   "a question is posted every 10 minutes" or "ask us and we will send one">.

The microphone permission is requested only when you tap dictation in a
text field on a muster page.
```

## Content rating (IARC questionnaire)

Category: **All other app types** (a utility / productivity / communication
app, not a game).

| Question | Answer | Why |
|---|---|---|
| Violence, fear, sexuality, crude humour, language, drugs, alcohol, tobacco, gambling | No | The app ships no such content; it shows the user's own server. |
| Does the app let users interact or exchange content with other users? | **Yes** | People who share a muster server see each other's tasks, comments and answers. |
| Does the app share the user's current physical location with other users? | No | No location access. |
| Does the app allow users to purchase digital goods? | No | |
| Does the app contain or promote gambling? | No | |
| Is it a web browser or a search engine? / unrestricted internet access? | No | The shell opens only the server the user added (and the sign-in pages that server names); every other link opens in the user's own browser. |
| Does the app generate content with AI? | Answer per Console's current wording: the app itself generates nothing; it displays what agents on the user's own muster server produce. | |

## Target audience and content

- **Target age:** 18 and over only. (Keeps the app out of the Families
  policy; it is a tool for operating a server.)
- **Ads:** No, the app contains no ads.
- **Government app:** No. **Financial features:** none. **Health:** No.
- **News app:** No.

## Data safety form

Play defines "collection" as "transmitting data from your app off a user's
device", including through WebViews the app controls
([Play Console Help 10787469](https://support.google.com/googleplay/android-developer/answer/10787469)).
It does not address apps whose only destination is a server the user runs
and chooses. Two readings:

- **Recommended (conservative): declare what the app sends to the user's
  muster server as collected**, not shared, for app functionality. It cannot
  be called inaccurate, and every row is true.
- Alternative: "No data collected", on the grounds that the developer
  receives nothing. Shorter, but a reviewer applying the definition literally
  could call it a misdeclaration. **Operator's call.**

Answers for the recommended reading:

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | Yes |
| Is all of the user data collected by your app encrypted in transit? | Yes (HTTPS only, system CAs; notifications are also Web Push encrypted) |
| Do you provide a way for users to request that their data is deleted? | Yes: "Remove this server" deletes everything on the phone and ends the pairing on the server; data the server holds is deleted by its operator (the privacy policy says so). |
| Is data shared with third parties? | No, for every type below. (ntfy carries only encrypted messages; a server the user chose is not a third party of the developer.) |

Data types, all **Collected: Yes, Shared: No, Processed ephemerally: No**,
purpose **App functionality** (and **Account management** where marked):

| Type | Required or optional | What it is |
|---|---|---|
| Personal info → Email address | Optional | Typed into the server's sign-in page (a server may use another sign-in). Account management. |
| Personal info → User IDs | Required | The account on the server; the device credential the server issues. Account management. |
| Messages → Other in-app messages | Optional | Answers to agent questions, comments typed on muster's pages. |
| App activity → Other user-generated content | Optional | Tasks and other content created on the server's pages. |
| App activity → App interactions | Required | Actions taken from notifications (answer, dismiss). |
| Audio → Voice or sound recordings | Optional | Dictation, only when the user dictates on a muster page. |
| Files and docs | Optional | Only files the user picks for upload. |
| Photos | Optional | Only images the user picks for upload. |
| Device or other IDs | Required | The pairing's device name (manufacturer and model), the step-up public key and the push address registered with the server. |

Not collected: location, contacts, calendar, financial info, health,
web browsing history, installed apps, crash logs, diagnostics, advertising ID.
No SDK in the app collects anything (no Google Play services, Firebase,
analytics, ads or crash reporting: [play-store.md](play-store.md), audit).
