[简体中文](../cn/TESTING.md) | [English](TESTING.md)

# Feature testing guide

After building and installing the app, use these steps to check connection, recording, voice Q&A, and notifications. See [compatibility](COMPATIBILITY.md) for tested devices and systems and [test records](AUDIT.md) for version-specific results. When sharing results, include the commit and device environment, and redact recordings, questions, notifications, and logs.

## Prerequisites

Complete [first setup](BUILDING.md): enter the glasses address, grant Bluetooth permissions, pair when required, configure your own service keys, and enable **实时识别，边说边显示提问** (real-time recognition). Notifications default to **仅监听检查** (observe-only); verify normal phone alerts before turning it off. Do not repeatedly factory-reset.

Local recording needs no cloud key. Streaming ASR and model Q&A use configured services; run these only when ready to upload test speech. The prerecorded-audio diagnostic button depends on a development fixture that is not included, so it does not work on a fresh install. Use **选择音频上传转写** to explicitly choose your own file instead. Chinese UI labels are retained here to help identify the actual controls.

| Scenario | Action | Pass condition |
| --- | --- | --- |
| Connection/standby | Save configuration, connect, and wait for standby | Actual authentication success and standby ready; a flashing blue LED alone is not a successful connection |
| Local recording | Start on the Recording page, speak for 10–20 seconds, stop/save, and play | A playable phone file, clear speech, plausible duration; note progress-bar issues; no cloud upload |
| Streaming voice | Wake the glasses and ask a short question | The question appears progressively while speaking, followed by an answer on the glasses |
| Two consecutive rounds | Wake again as the first answer appears and ask a different question | The second round works without touching the phone; questions/answers match and old results do not overwrite the new round |
| Screen off | Turn the phone screen off, wait one minute, then wake the glasses and ask | Capture continues, question text appears, and an answer completes without a brief flash/exit; one minute is not overnight reliability |
| Notifications | Grant access, select apps, enable forwarding; check phone alerts in observe-only first, then turn it off and receive a selected app's notification | Normal phone alert and complete glasses message; each notification has its own ID; burst queuing is still an optimization item |
| Optional knowledge experiment | Configure the dedicated Gateway and ask | Answer appears on glasses; phone sources match the server's structured `sources`; an empty array does not prove retrieval |

## Idle check

With standby ready and no active recording/question, agree on three minutes without wake words, button presses, or app requests. From the repository root with ADB configured:

```powershell
python idle_check.py --serial "YOUR_PHONE_SERIAL" --seconds 180
```

The tool takes only two snapshots, at the beginning and end. It checks the same live session's cumulative recorder requests, audio packets, uploads, business submissions, and streaming cloud connections, plus standby state at both ends. `passed` means these counters did not increase within that scope. A changed session or nonzero delta requires investigation; it is not automatically an unauthorized background call. Record intentional wake-ups and arrange a new idle window.

Output is saved under `out/power-tests/` and includes raw state; redact it before sharing. This tool checks app counters, not Bluetooth traffic, current draw, cloud bills, or vendor SDK heartbeats. Long-term power consumption requires separate measurement.

## Records and lessons

For each test record: date, version/hash, phone/OS, firmware (write unknown if unknown), prerequisites, steps, protocol result, wearer observation, whether cloud services were called, anomalies, and redacted evidence. Use the [observer](OBSERVER.md), but do not treat its reconstructed view as real lens confirmation.

Past problems included a missing initial device address, streaming ASR off by default, observe-only notifications not forwarding, OS background limits, follow-up timing while an answer is displayed, and capture exiting with the phone screen off. Include those conditions in testing; repeated pairing is not a substitute for diagnosis. Notification latency/bursts, long recordings, sustained power use, and process/restart recovery need dedicated validation. For changes to diagnostic entry points or session lifecycles, also verify normal internal connection after entry-point isolation and that failed/duplicate instances cannot clean up a live session. See [security mechanisms](SECURITY.md) and the [audit index](AUDIT.md) for validation status.
