[简体中文](../cn/COMPATIBILITY.md) | [English](COMPATIBILITY.md)

# Compatibility and acceptance scope

The current source preview is Android sample app 0.13. SEC-01/02 source fixes, independent review, 45 automated tests, and build checks have passed; full device regression is still pending. Neither a 0.12 nor a 0.13 APK is included.

The table below records **historical 0.12** device results: RayNeo iO with a Xiaomi MIX Fold 4 (24072PX77C), Android 16 / API 36. The dependency baseline is official Android app 1.0.2 (68). The glasses firmware version has not been added to this public matrix. These results cannot establish compatibility with arbitrary firmware or mark all 0.13 functions as passed.

The sample Manifest declares minimum API 29; that does not mean APIs 29–35 were all tested. Most device evidence comes from one phone and one pair of glasses. Wider coverage is needed.

| Scenario | Historical acceptance result |
| --- | --- |
| Owned-device connection, authentication, and text notifications | Business acknowledgments and wearer confirmation |
| Glasses microphone → streaming ASR → question text → answer | Wearer confirmation; no need to substitute the phone microphone |
| Consecutive questions | Several successful rounds after fixes to old-round cleanup and follow-up triggering; not proof for every `type11` situation |
| Screen-off voice Q&A | Wearer confirmation; not proof of deep sleep without USB or every ROM policy |
| Recording, saving, and playback | Short recordings confirmed; the longest retained segment was about 56 seconds. The five-minute limit and failure recovery require dedicated testing |
| Feishu notifications | Normal phone alerts and complete glasses display; other apps and high-frequency traffic need separate tests |
| Idle calls | During an agreed three-minute no-operation window, 19 samples found no new capture, ASR, or cloud upload and the remote task count stayed unchanged; not an overnight power conclusion |
| Remote knowledge Q&A | An end-to-end answer and sources for a specific question were observed; citations for natural voice questions remain inconsistent. This does not establish sufficient usability or stability for the experimental Gateway |

Known issues include intermittent playback-progress inconsistency; unresolved burst-notification stacking/update behavior, with separate IDs retained; incomplete remote/local cancellation coordination; connection dependence on a research Activity's lifecycle; and incomplete testing of long-term power, case placement/removal/wear detection, process termination, and restart recovery.

Public test records should include versions, steps, results, and acceptance conditions, without real notifications, user questions, unique device identifiers, or private evidence-directory paths. A send-success callback alone is not lens-display acceptance.
