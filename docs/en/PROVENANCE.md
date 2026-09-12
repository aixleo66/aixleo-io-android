[简体中文](../cn/PROVENANCE.md) | [English](PROVENANCE.md)

# Provenance and Turbo IO attribution

This project draws on the iOS implementation and research documentation of [Turbo1123/Turbo-IO](https://github.com/Turbo1123/Turbo-IO). Thanks to its author and contributors. The Android implementation is maintained separately and has not received upstream endorsement.

The upstream baseline used for source comparison is [`9382ec6791c70cb97546ffc0a08ca87bc3050746`](https://github.com/Turbo1123/Turbo-IO/tree/9382ec6791c70cb97546ffc0a08ca87bc3050746). This is the comparison baseline, not necessarily the first commit from which every reference was taken, nor evidence that every item has the same historical license.

## Confirmed reference relationships

| Android location / topic | Upstream evidence or research | Relationship currently supported by evidence |
| --- | --- | --- |
| `analysis/check-synthetic-auth.py` | `rayneo-protocol/Tests/RayNeoProtocolTests/ProtocolTests.swift` | File comments explicitly attribute expected test vectors to Turbo IO. The formula also draws on decompilation of the official Android app. The specific licenses of vectors and implementation still require item-level review. |
| `app/src/VoiceWakePolicy.java` and voice session implementation | Turbo IO standby/follow-up research and iOS voice logic | Code comments and development records reference type1/type11 triggers and the answer window. Whether specific expression was ported, the corresponding source files, and the first referenced version still need confirmation. |
| Ordinary recording control and decoding | `CompanionDeviceFeatures.swift`, `core-probe/Sources/NativeRecording.c` | Used to compare recording requests and decoding parameters. The Android implementation was also adjusted using official message models and real-device acknowledgements. |
| Channel and transcription research | `ManualRecordingASR.swift` and upstream architecture notes | Used to investigate left/right channels and mixing. This does not mean Android implements all upstream processing features. |
| Browser observer | Upstream display observation design and notes | References protocol redrawing and the distinction between submitted text and actual lens output. Android uses its own USB ADB state-reading path. |
| Vendor connection adapter | Vendor components from the official Android app | Directly uses official Android vendor components; their provenance and licensing are listed separately in the third-party notices. |

These are the reference relationships documented so far. Complete file-level provenance and licensing review is still in progress.

`analysis/check-synthetic-auth.py` is a historical research script not included in this repository; the table records the source of its test vectors. Other Android paths refer to this repository, while iOS paths refer to the upstream project.

For licensing and dependency information, see [Licensing](LICENSING.md) and [Third-party notices](THIRD_PARTY_NOTICES.md).
