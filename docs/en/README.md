[简体中文](../cn/README.md) | [English](README.md)

> Noncommercial research source preview. Includes source code, pinned vendor dependencies, documentation, and build/tests; no prebuilt APK is currently included. Permission to redistribute vendor components has not been verified; a noncommercial purpose does not replace permission.

> This page describes features and usage boundaries on the current branch; it does not establish device acceptance of every feature at the current commit. See the [audit index](AUDIT.md) for version-specific completed and pending checks, the [changelog](CHANGELOG.md) for version differences, and [security](SECURITY.md) for debug capabilities and limits.

# Aixleo iO Android

An unofficial Android companion app and experimental SDK for RayNeo iO.

An interoperability research project for developers, with an Android companion app for exploring glasses connectivity, voice input, text display, recording, and phone notification forwarding.

**Acknowledgment:** Development references the iOS implementation and research documentation of [Turbo1123/Turbo-IO](https://github.com/Turbo1123/Turbo-IO), including device communication and sessions, voice assistant interaction, recording, and display observation. We thank the author and contributors. This is a separately maintained Android implementation, not an official Android port released or endorsed by the Turbo IO author. We do not claim that every implementation is independently original. See [provenance](PROVENANCE.md) for references, adaptations, and licensing scope.

**This project is not affiliated with, sponsored by, or endorsed by RayNeo or its related companies.** RayNeo / 雷鸟 iO names identify the compatible device. This is an experimental project, not a stable standalone SDK API or a complete replacement for the official app.

Users need Android development and debugging experience, their own toolchain, signing key, and service configuration. Device communication still depends on a specific version of vendor code. Vendor and original code have separate licensing scopes; see [building](BUILDING.md) and [third-party notices](THIRD_PARTY_NOTICES.md).

The source preview includes `vendor/rayneo-venus-1.0.2-68/vendor-payload.jar`, containing three unmodified official DEX files and two coroutine service declarations. Their origin is identified separately; they are not claimed as original work or licensed by this project's root license. The repository excludes prebuilt APKs, the complete official APK, private signing keys, service credentials, and user data.

**The pinned vendor dependency is included; users do not need to extract it again.** Build your selected commit with your own toolchain and signing key before installing it. This is not an implementation independent of vendor code, and compatibility with other dependency versions is not guaranteed. See [build and setup instructions](BUILDING.md).

## Features and historical validation

The table summarizes implemented features and limitations. See [compatibility records](COMPATIBILITY.md) for the versions and devices associated with real-device evidence, and the [audit index](AUDIT.md) for validation status. Historical passes do not automatically cover subsequent changes.

| Feature | Status and limits |
| --- | --- |
| Device connection | Pairing and authentication on owned devices; reconnecting to a saved device; opening the app attempts connection and voice standby |
| Glasses voice assistant | Glasses wake-up and microphone capture, streaming transcription displayed incrementally, model answers on the glasses; consecutive questions and screen-off use have historical device results |
| Recording | Start/stop glasses recording from the phone; keep original data and a WAV, and play it on the phone; currently limited to five minutes per segment, with long-duration boundaries not fully validated |
| Phone notifications | User-authorized forwarding from selected apps, with a distinct ID per notification; real Feishu notifications were verified; other apps need separate testing |
| Experimental remote Q&A | Android connection adapter only; no Rokid Harness server. One exploratory way to connect to local Codex, without sufficient usability or reliability validation for this version; more flexible approaches are being explored |
| Browser observer | Reads app protocol state over USB and redraws text; not a lens screenshot or complete historical archive |

In this project, RayNeo iO is a microphone input and display device. It has no camera or speaker capability usable by this project. Configured external services perform ASR and model inference; the project does not run a large language model on the glasses.

## Data and costs

- Ordinary recordings stay on the phone by default; saving does not automatically upload them for transcription.
- After a voice round is triggered, its audio goes to the configured ASR service. Valid recognized text goes to the selected Q&A service. Accidental wake-ups may also produce requests.
- Standby does not continuously upload audio to ASR. A historical three-minute idle observation found no new recording, transcription, or model tasks; it does not establish zero power use or rule out accidental wake-ups.
- Notification forwarding does not call a model. Notification text may appear on the glasses and in diagnostic previews.
- Users supply their own keys and services and pay according to those services' terms. No shared quota or maintainer service account is included.

See [permissions and privacy](PRIVACY.md).

## Before connecting

Connect only devices you own or are explicitly authorized to use. Switching from the official app may require addressing an existing binding. Official unbinding may erase glasses data and restore factory settings; read the device prompt and back up first. Do not use factory reset for routine reconnection, or let two clients compete for the same device.

Install and launch from the phone app. `lab.py run --execute` rejects the legacy externally launched diagnostic path before installing or operating on a phone. Observer and command tools for an existing session may still read state, send messages, or call cloud services; read their parameters and effects first.

## Current limitations

- No glasses firmware modification, replacement launcher, or arbitrary native UI drawing.
- No guarantee of automatic recovery after process termination, phone restart, or every disconnect.
- No guarantee across all phones, firmware, long recordings, notification bursts, or offline transfer scenarios.
- Cancelling the wait on the phone does not necessarily stop an already submitted remote model task.
- No official historical-data migration, signing service, paid configuration service, or vendor service credentials.

See [compatibility](COMPATIBILITY.md), [testing](TESTING.md), [observer instructions](OBSERVER.md), and the [experimental Gateway protocol and excluded server scope](GATEWAY.md).

## Structure and development direction

The code is still centered on the Android sample app. Future work will separate connection/transport, sessions, recording, display, and model adapters so that more logic can be tested independently. This planned modularization is not a completed AAR SDK.

## Development approach and feedback

This project is developed and tested incrementally by the maintainer in collaboration with Codex, using a vibe coding approach. The code, documentation, and test coverage may contain bugs or omissions. Feedback from people using the project helps identify and improve them.

Previous device experiments have demonstrated the core connection, voice Q&A, recording, and notification workflows. This does not mean every interaction detail or failure scenario is complete; logic errors may remain. We are conducting focused tests of these details while continuing to iterate on features. See [audit records](AUDIT.md) for automated checks and device validation of new changes. A code fix does not mean every scenario has passed.

If you encounter a bug, compatibility issue, or documentation gap, or have a feature suggestion, please open a [GitHub Issue](https://github.com/aixleo66/aixleo-io-android/issues). If you already have another way to contact the maintainer, you are welcome to reach out directly.

When reporting a problem, include the project version or commit, device model, OS version, firmware if available, reproduction steps, expected and actual results, and redacted logs where possible. Do not submit keys, recordings, complete notifications, or other people's information. Code and documentation contributions should identify their sources and applicable licenses.

## License and third-party rights

Original contributions that the project has the right to license use PolyForm Noncommercial 1.0.0 for noncommercial learning, interoperability research, and exploration. The [root LICENSE](../../LICENSE) controls. Third-party content retains its own license; vendor components and trademarks do not change ownership through combined use. Do not describe the entire package as original or covered by the root license.

See [licensing scope](LICENSING.md). A statement of purpose does not replace required permission, and including vendor components does not establish that their rights holders authorized redistribution.

## Documentation index

- [Build and initial setup](BUILDING.md)
- [Compatibility and limitations](COMPATIBILITY.md)
- [Test procedures and evidence](TESTING.md)
- [Browser observer](OBSERVER.md)
- [Experimental Gateway protocol](GATEWAY.md)
- [Permissions and privacy](PRIVACY.md)
- [Security status](SECURITY.md)
- [Independent audit record](AUDIT.md)
- [Source preview scope](RELEASE-CHECKLIST.md)
- [Provenance and attribution](PROVENANCE.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)
- [Licensing scope](LICENSING.md)
- [Changelog](CHANGELOG.md)
- [Documentation maintenance](DOCUMENTATION.md)
