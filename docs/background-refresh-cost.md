# What the background refresh job costs (emulator measurements)

The background refresh job (`RefreshWorker`, 0.1.2) is a WorkManager periodic
job for paired phones. It runs every 15 minutes, with network connected and
battery not low. Each run reads `/api/device/config` and starts Chromium in the
app's process. This page records what one run costs, **as measured on an
emulator**, and what an emulator cannot tell you.

## How it was measured

- **Device:** a headless Android emulator, API 36 `google_apis` x86_64,
  WebView 133, swiftshader GL, `-no-snapshot`.
- **Server:** a local muster in password mode, reached over TLS. The emulator
  trusted a throwaway CA through its system store (root plus a bind mount).
  The app was paired through the real flow: Account → Devices → Pair this
  phone. The emulator paired read-only, because its keystore is software.
- **App:** a debug build, which carries `RunRefreshReceiver`. The job code is
  the same in every build.
- **Each run:**
  1. Kill the app's process (`kill -9` as root, so Android records it as
     signalled, not user-stopped).
  2. Reset batterystats and unplug the emulated battery (`dumpsys battery
     unplug`), so batterystats records.
  3. Start the job.
  4. Read logcat, `/proc/<pid>/stat`, `dumpsys meminfo`, `dumpsys
     batterystats`, `dumpsys alarm` and `dumpsys jobscheduler`.
- **How the job was started:**
  - 14 runs with `adb shell am broadcast -n dev.zacx.muster/.RunRefreshReceiver`
    (debug only). This enqueues the same worker as one-time work with no
    constraints. WorkManager starts it at once in the app's process (its
    in-process GreedyScheduler, since this one-time work has no constraints)
    and also schedules a JobScheduler job for it; that job's `onStartJob`
    arrives a moment later and finds the work already running. batterystats'
    job time and job wakelock in these runs are that JobScheduler job's.
  - 1 run of the real periodic job, left to fire on its own schedule with the
    process dead. That run starts from JobScheduler (`SystemJobService`), so
    the two paths differ: the broadcast runs measure the worker and the
    process start, not how JobScheduler launches the periodic job.
- **Two kinds of run:**
  - **Polled** (6 runs): `dumpsys meminfo` every ~50 ms while the job ran,
    for the peak PSS. A meminfo dump runs code in the app's process, so these
    runs' CPU figures are not used.
  - **Quiet** (8 runs): nothing touched the app until about 8 s after the job
    finished. CPU figures come from these runs.

## Results

Ranges are over the runs named. "Natural" is the one periodic run that fired
on its own schedule.

| | broadcast-started runs | natural periodic run |
|---|---|---|
| Wall time, process start → `Worker result SUCCESS` | 0.70–1.51 s, median 1.21 s (14 runs) | 1.04 s |
| Of which Chromium start (`WebViewCompat.startUpWebView`) | 71–183 ms | 85 ms |
| CPU, app process, user+system (`/proc/<pid>/stat`, ~8 s after the job) | 0.79–1.19 s, median 0.83 s (8 quiet runs) | 0.84 s (batterystats: u=738 ms, s=102 ms) |
| PSS of the app process, peak while the job runs | 92–101 MB (6 polled runs) | not sampled |
| PSS of the app process, ~8 s after the job (left cached) | 55.9–56.4 MB (8 quiet runs) | 56.5 MB |
| PSS of the WebView renderer process the job starts | 8.2–8.5 MB at the end, ~5.6 MB 10 s later | 7.6 MB |
| JobScheduler job time (holds the job wakelock) | 0.46–1.24 s (`Job …SystemJobService`, batterystats) | 0.23 s (see below) |
| Wakelocks held by the app | only JobScheduler's job wakelock (`*job*r/@androidx.work.systemjobscheduler@dev.zacx.muster/…SystemJobService`); none of its own | the same |
| Alarms set by the app | none: 0 of the 44 pending alarms in `dumpsys alarm` were the app's | none |
| Network (Wi-Fi, batterystats) | 2.9 KB received, 1.2 KB sent, 8/9 packets (one TLS handshake + one GET) | the same |

Notes:

- **Before a run there is no process.** The process the job starts is left
  cached afterwards. The ~56 MB plus the renderer is what stays resident until
  Android reclaims the process. Android decides how long that is; the app does
  nothing to keep it alive.
- **The natural run was interrupted, then ran.** When the job started a cold
  process, WorkManager's start-up reconcile ("Found unfinished work, scheduling
  it") rescheduled the job and stopped the first `onStartJob`. The same process
  then started the work again 24 ms later, and it finished (`CONFIG_READ`,
  `Worker result SUCCESS`). JobScheduler logged that run as `canceled(1x)
  successful_finish(1x)`, with 0.23 s of job time. The work itself ran for
  0.81 s.
- **`cmd jobscheduler run -f` is not a reliable way to start this job.**
  Forcing the periodic job into a cold process hit the same reconcile. The
  forced run was stopped ("Status … is ENQUEUED; not doing any work"). In 15
  of 16 forced runs the work did not run again before the run ended (about
  10 s); in 1 it ran 2 s later. That is why the broadcast receiver is used.
- **Worst case per day:** 96 runs (one per 15 minutes) × ~0.8 s is about 77 s
  of CPU on this emulator, and 96 × ~4 KB of network. Doze and App Standby
  stretch the period while the phone is idle, so a real day has fewer runs.

## What an emulator cannot tell you

- **Battery drain.** An emulator has no real battery, radio or power rails.
  Its batterystats "estimated power" numbers come from a generic power
  profile, not a measurement, so none are quoted here. These things decide the
  real cost:
  - the CPU's frequency and power states;
  - how long the Wi-Fi or cellular radio stays in a high-power state after
    the 4 KB exchange (the radio tail often costs more than the bytes);
  - how often a run wakes the phone from suspend.

  Only a real phone can show these: `dumpsys batterystats` on battery over a
  day, or Battery Historian, or a power monitor.
- **How often it runs.** The emulator was awake and not in Doze. On a real
  phone, Doze, App Standby buckets and the OEM's own battery management
  decide how often a 15-minute job actually runs. Some OEMs defer such jobs
  for hours or kill them.
- **CPU time on a phone.** The x86_64 emulator on a desktop CPU is not an
  ARM phone. The phone's absolute times will differ, and so will Chromium's
  start-up time.
- **Memory pressure.** How long Android keeps the cached process, and whether
  keeping it pushes other apps out, depends on the phone's RAM and what else
  runs.

## Verdict

Nothing measured here shows a clear problem:
- about 1 s per run;
- under 1 s of CPU;
- one small request;
- no wakelock or alarm of the app's own.

No code was changed. The open question is the battery cost on a real phone,
above all the radio wake-ups, and that needs a real phone.
