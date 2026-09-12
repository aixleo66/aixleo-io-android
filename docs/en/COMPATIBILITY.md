[简体中文](../cn/COMPATIBILITY.md) | [English](COMPATIBILITY.md)

# Compatibility and acceptance scope

This page summarizes tested devices, operating systems, and feature scenarios, along with limitations still under investigation. See [test records](AUDIT.md) for automated checks and build results by version.

The table below records **historical 0.12** device results: RayNeo iO with a Xiaomi MIX Fold 4 (24072PX77C), Android 16 / API 36. The dependency baseline is official Android app 1.0.2 (68). The glasses firmware version has not been added to this public matrix. These results cannot establish compatibility with arbitrary firmware or mark all functions in later versions as passed.

**Glasses connection currently requires Android 12+ / API 31.** The Manifest's minSdk 29 is only the installation declaration. Connection initialization explicitly rejects API < 31, so this connection path does not support Android 10/11; it is not merely untested. APIs 31–35 still lack corresponding device validation; historical device evidence comes from API 36. Most evidence is from one phone and one pair of glasses. Wider coverage is needed.

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

If you use another phone or firmware version, share the version, device environment, reproduction steps, and redacted results in an Issue to help expand compatibility coverage.
