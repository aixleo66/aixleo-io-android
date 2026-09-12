[简体中文](../cn/README.md) | [English](README.md)

# Aixleo iO Android

**An unofficial Android SDK for RayNeo iO, with an Android example app you can build yourself.** It gives developers a starting point to explore glasses recording, voice assistants, text display, and phone notifications.

The project draws on the iOS implementation and research documentation of [Turbo1123/Turbo-IO](https://github.com/Turbo1123/Turbo-IO). Thanks to its author and contributors for sharing their work. The Android implementation is maintained separately; see [provenance and attribution](PROVENANCE.md) for the reference relationships.

This is experimental software. SDK interfaces are not stable, the code is still centered on the example app, and no standalone AAR is available yet. The project is not affiliated with or endorsed by RayNeo or the Turbo-IO author. It is intended for noncommercial research and learning.

## Implemented features

| Feature | Description |
| --- | --- |
| Device connection | Pairing, authentication, and reconnection to a saved device; opening the app attempts connection and voice standby |
| Glasses voice assistant | Captures audio from the glasses microphone, shows incremental transcription and model answers; consecutive questions and phone-screen-off Q&A have been demonstrated |
| Recording | Start and stop glasses recording from the phone, save raw audio and WAV, and play it on the phone; currently limited to five minutes per recording |
| Phone notifications | Select apps to forward after granting permission; each notification is displayed separately; Feishu notifications have been tested |
| Browser observer | Reads app state over USB and redraws text sent to the glasses for development and debugging |
| Experimental knowledge integration | Android Gateway adapter exploring access to computer-side Codex and knowledge bases; requires a separately provided compatible server, with stability still under testing |

RayNeo iO provides microphone input and text display here. Recognition and Q&A run through external services you configure. The project uses no camera or speaker. The observer redraws protocol content; checking actual lens output still requires wearing the glasses.

## Getting started

Source code and build instructions are available; no prebuilt APK is currently provided. The repository includes the pinned vendor communication dependency needed for building, so you do not need to extract it again. See [third-party notices](THIRD_PARTY_NOTICES.md) for its version and provenance.

1. Prepare RayNeo iO glasses, an Android 12 or newer phone, and a Windows x64 development environment. Follow [building and initial setup](BUILDING.md) to build and install the app. See [compatibility](COMPATIBILITY.md) for device, OS, and firmware coverage.
2. Enter the glasses Bluetooth address in the app, grant permissions, and connect. Address entry is currently manual; there is no scan-and-select device list yet.
3. Start with local recording. The voice assistant needs your own ASR and model service keys. Notification forwarding additionally requires notification access and app selection.

When switching from the official app, unbinding may erase glasses data and perform a factory reset. Back up first and read the device prompts. Routine reconnection does not require repeated factory resets; avoid connecting two clients to the same glasses at once.

## Data and costs

- Ordinary recordings stay on the phone and are not automatically uploaded for transcription.
- After waking, the voice assistant uploads audio to your configured ASR service and sends valid questions to your Q&A service. Real-time mode uploads during capture; accidental wake-ups can also incur calls.
- Standby does not continuously upload ASR audio. Long-term power consumption and accidental wake-ups remain under testing.
- Notification forwarding makes no model calls, but notification bodies may appear on the glasses and in the observer.
- Cloud services use your own accounts and provider pricing. Cancellation cannot retract uploaded data and may not stop tasks already accepted remotely.

See [Privacy](PRIVACY.md) for data destinations and controls. The app is currently a debug build; see [Security](SECURITY.md) for ADB and observer access boundaries.

## Development status and feedback

The maintainer develops this project together with Codex through vibe coding. Core connection, voice Q&A, recording, and notification workflows have been demonstrated, but interaction details and error handling may still contain logic bugs. Focused testing and feature development are ongoing. You are welcome to build it, experiment, and share feedback.

Long recordings and the five-minute boundary, burst notification queues, sustained power use, and recovery after process termination or restart still need further testing. Other notification apps need individual verification. Firmware changes, a replacement native launcher, and arbitrary native UI rendering are outside the current feature set. See [test records](AUDIT.md) for results and the [changelog](CHANGELOG.md) for changes.

For bugs, documentation gaps, and suggestions, open an [Issue](https://github.com/aixleo66/aixleo-io-android/issues), or use an existing contact channel with the maintainer. Include the version or commit, device and OS information, reproduction steps, and redacted logs. Do not upload keys, recordings, full notifications, or other personal data.

Next, we plan to separate connection, session, recording, display, and model adapters to make reuse in other Android projects easier.

## License

Original portions that this project has the right to license use [PolyForm Noncommercial 1.0.0](../../LICENSE). Third-party components retain their respective licenses; vendor components are not covered by the root license. See [Licensing](LICENSING.md) for the scope and dependency authorization status.

## Documentation index

- [Build and initial setup](BUILDING.md)
- [Compatibility and limitations](COMPATIBILITY.md)
- [Test procedures and evidence](TESTING.md)
- [Browser observer](OBSERVER.md)
- [Experimental Gateway protocol](GATEWAY.md)
- [Permissions and privacy](PRIVACY.md)
- [Security status](SECURITY.md)
- [Test and audit records](AUDIT.md)
- [Release checklist (maintainers)](RELEASE-CHECKLIST.md)
- [Provenance and attribution](PROVENANCE.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)
- [Licensing scope](LICENSING.md)
- [Changelog](CHANGELOG.md)
- [Documentation maintenance](DOCUMENTATION.md)
