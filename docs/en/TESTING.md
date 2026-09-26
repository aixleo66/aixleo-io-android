[简体中文](../cn/TESTING.md) | [English](TESTING.md)

# Feature testing guide

After building and installing the app, use these steps to check connection, recording, voice Q&A, todos, weather and notifications. See [compatibility](COMPATIBILITY.md) for tested devices and systems and [test records](AUDIT.md) for version-specific results. When sharing results, include the commit and device environment, and redact recordings, questions, notifications, and logs.

## Prerequisites

Complete [first setup](BUILDING.md): exit the official app process, pair when needed, select your glasses in this app, and wait for the session to become ready. Recording needs no cloud service. Assistant Q&A needs your own speech and model service configuration. Notifications require system access and a selected source. Do not treat unbinding or factory reset as a routine reconnect step.

Cloud speech and model tests can upload personal content and incur charges; use only your own nonsensitive samples. For offline-file transcription tests, explicitly choose an authorized test file.

| Scenario | Action | Pass condition |
| --- | --- | --- |
| Connection/standby | Connect a paired pair of glasses and inspect authentication, state, and standby | Session readiness; LED, battery, or one authentication callback alone is insufficient |
| Local recording | Record about 20 seconds from the phone or glasses menu; stop and play it in the phone recording list | Playable Ogg Opus file and plausible duration; recording does not automatically transcribe |
| Assistant/follow-up | Ask a short question, observe the lens answer, then ask another within the follow-up window | Distinct matching rounds with no stale result overwrite; inspect exit and next wake-up |
| Todo | Add and sync a test item on the phone, complete it on the glasses, then check the phone | Both sides agree, and another sync creates no duplicate |
| Weather/clock | Grant location and connect; inspect temperature and time on the glasses | Temperature has a value and time matches the phone; one round does not prove all-day refresh |
| Notifications | Grant Android notification access, select a source, and cause a real system notification | It appears in the phone notification shade first; the wearer then confirms the lens message |
| Optional knowledge experiment | Configure your own Gateway and ask | Check the answer and sources on the phone; an empty source list does not prove retrieval |

## Idle check

With standby ready and no active recording/question, agree on three minutes without wake words, button presses, or app requests. From the repository root with ADB configured:

```powershell
python idle_check.py --serial "YOUR_PHONE_SERIAL" --seconds 180
```

The tool takes two snapshots, at the beginning and end, and compares the same session's cumulative recording requests, audio packets, cloud uploads, business submissions, streaming cloud connections, and standby state. `passed` means the counters did not grow within that scope. If the session changes or counters increase, investigate the actions and logs from that period. Restart the idle check after an intentional wake-up.

Output is saved under `out/power-tests/` and includes raw state; redact it before sharing. This tool checks app counters, not Bluetooth traffic, current draw, cloud bills, or vendor SDK heartbeats. Long-term power consumption requires separate measurement.

## Records and lessons

For each test record: date, version/hash, phone/OS, firmware (write unknown if unknown), prerequisites, steps, protocol result, wearer observation, whether cloud services were called, anomalies, and redacted evidence. Use the [observer](OBSERVER.md), but do not treat its reconstructed view as real lens confirmation.

Known limits include occasional connection loss, long-answer exit timing, knowledge citations, in-place notification updates, and long recording. If connection fails, first check whether the official app still owns the session and whether this app is truly ready; repeated unbinding is not a diagnosis. All-day background operation and other phones and firmware need separate validation. See the [feature limits](FEATURES-0.2.1.md) and [troubleshooting](TROUBLESHOOTING.md).
