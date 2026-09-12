[简体中文](../cn/PROVENANCE.md) | [English](PROVENANCE.md)

# Provenance and Turbo IO attribution

This project draws on the iOS implementation and research documentation of [Turbo1123/Turbo-IO](https://github.com/Turbo1123/Turbo-IO). Thanks to its author and contributors. The Android implementation is maintained separately and has not received upstream endorsement.

The upstream baseline used for source comparison is [`9382ec6791c70cb97546ffc0a08ca87bc3050746`](https://github.com/Turbo1123/Turbo-IO/tree/9382ec6791c70cb97546ffc0a08ca87bc3050746). This is the comparison baseline, not necessarily the first commit from which every reference was taken, nor evidence that every item has the same historical license.

## Confirmed reference relationships

| Android location / topic | Upstream evidence or research | Relationship currently supported by evidence |
| --- | --- | --- |
| `analysis/check-synthetic-auth.py` | `rayneo-protocol/Tests/RayNeoProtocolTests/ProtocolTests.swift` | File comments explicitly attribute expected test vectors to Turbo IO. The formula also draws on decompilation of the official Android app. The specific licenses of vectors and implementation still require item-level review. |
| `app/src/VoiceWakePolicy.java` and voice session implementation | Turbo IO standby/follow-up research and iOS voice logic | Code comments and development records reference type1/type11 triggers and the answer window. Whether specific expression was ported, the corresponding source files, and the first referenced version still need confirmation. |
| Ordinary recording control and decoding | `CompanionDeviceFeatures.swift`, `core-probe/Sources/NativeRecording.c` | Used to compare recording requests and decoding parameters. Android was also adjusted using official message models and real-device acknowledgements; the final behavior cannot all be characterized as an unmodified port. |
| Channel and transcription research | `ManualRecordingASR.swift` and upstream architecture notes | Used to investigate left/right channels and mixing. This does not mean Android implements all upstream processing features. |
| Browser observer | Upstream display observation design and notes | References protocol redrawing and the distinction between submitted text and actual lens output. Android uses its own USB ADB state-reading path. |
| Vendor connection adapter | Vendor components from the official Android app | This is not material that Turbo IO can license on the vendor's behalf. Vendor rights and dependency provenance require separate review. |

This is a preliminary, evidence-supported inventory, not a line-by-line copying audit or a complete licensing determination. Design references, copied/translated code, test-vector use, and interface recovery must be recorded separately.

`analysis/check-synthetic-auth.py` is a historical research script retained locally and **is not included in this repository**. Its row documents research provenance; users are not expected to find or run it. Other Android source paths refer to this repository, while the iOS paths belong to the linked upstream repository.

## Recording provenance when contributing code

For each item, record at least: local file and range, upstream project and path, exact commit, license when obtained, type of reference, changes made, required copyright/license notices, and review conclusion. Mark unknown facts as pending verification; do not invent authors or authorization.

Copied or ported files must retain required original copyright and license notices in the relevant files/directories. A single README acknowledgement cannot replace them. The root LICENSE also cannot override vendor or other upstream rights.

See [Licensing](LICENSING.md) and [Third-party notices](THIRD_PARTY_NOTICES.md) for the current upstream license relationship and choices. Upstream endorsement or additional authorization for this Android project has not been obtained. Do not describe it as an “official Android version” or “authorized by the original author.”
