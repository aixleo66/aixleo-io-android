[简体中文](../cn/COMPATIBILITY.md) | [English](COMPATIBILITY.md)

# Compatibility and acceptance scope

This page summarizes tested devices, operating systems, and feature scenarios, along with limitations still under investigation. See [test records](AUDIT.md) for automated checks and build results by version.

The 0.2.1 version is supported mainly by development records from one phone and one pair of RayNeo iO glasses. The earlier public matrix used a Xiaomi MIX Fold 4 (24072PX77C) with Android 16 / API 36. The bundled communication dependency came from official Android app 1.0.2 (68), while device behavior was checked against later official-app and firmware versions. Each test still needs its own environment and version evidence; arbitrary firmware compatibility is not established.

**Glasses connection requires Android 12+ / API 31.** The Manifest's minSdk 29 is only the installation declaration; connection initialization rejects API < 31. Device coverage for APIs 31–35 is limited, while the main phone record uses API 36.

| Scenario | Scope supported for the 0.2.1 version |
| --- | --- |
| Owned-device connection, authentication, and text notifications | Business acknowledgments and wearer confirmation |
| Glasses microphone → streaming ASR → question text → answer | Wearer confirmation; no need to substitute the phone microphone |
| Consecutive questions | Several successful rounds after fixes to old-round cleanup and follow-up triggering; not proof for every `type11` situation |
| Screen-off voice Q&A | Wearer confirmation; not proof of deep sleep without USB or every ROM policy |
| Recording, saving, and playback | Local Ogg Opus save/playback and roughly minute-long segments have device records; long recordings, failure recovery, and wider phone compatibility remain open |
| Feishu notifications | Normal phone alerts and complete glasses display; other apps and high-frequency traffic need separate tests |
| Idle calls | During an agreed three-minute no-operation window, 19 samples found no new capture, ASR, or cloud upload and the remote task count stayed unchanged; not an overnight power conclusion |
| Remote knowledge Q&A | An end-to-end answer and sources for a specific question were observed; citations for natural voice questions remain inconsistent. This does not establish sufficient usability or stability for the experimental Gateway |

Known issues include occasional connection loss without a complete root cause, no in-place notification update, inconsistent citations for natural knowledge questions, and long-answer exit timing. Long recordings, cross-device conflicts, all-day background behavior, in-case battery refresh, and wider phone coverage remain open. See the [feature matrix](FEATURES-0.2.1.md) for feature-specific limits.

If you use another phone or firmware version, share the version, device environment, reproduction steps, and redacted results in an Issue to help expand compatibility coverage.
