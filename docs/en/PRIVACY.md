[简体中文](../cn/PRIVACY.md) | [English](PRIVACY.md)

# Permissions, data, and diagnostics

This page explains the permissions the app uses, where data is stored, and which actions access external services. See [Security](SECURITY.md) for debug builds and ADB access.

| Data or capability | Current use and destination |
| --- | --- |
| Bluetooth scanning and connection | Discovers and connects to glasses selected by the user, receives device events, audio, and state, and sends display/control messages. |
| Phone microphone | Used only when the user explicitly selects a phone microphone test. Normal glasses voice interaction uses the glasses microphone. |
| Ordinary glasses recording | Received audio, the local Ogg Opus file, and recording records are kept in the phone app's private directory. Saving does not automatically call cloud transcription. |
| Voice assistant audio | Sent to the user-configured ASR service after a voice turn is triggered. Real-time mode uploads while receiving audio; accidental wake-ups can therefore cause requests. |
| Questions and answers | Valid questions are sent to the selected model or remote Gateway. The phone retains recent results and sends answers to the glasses. Remote retention depends on the chosen service. |
| Phone notifications | After system notification access is granted, local code filters notifications by app/category. Selected content is sent to the glasses, not to a model. |
| Location and weather | Coordinates are rounded to two decimal places and sent over HTTPS to Open-Meteo. Location and fix time are cached in the phone app’s `weather` preferences. Rounded coordinates are still location data. Opening the weather page with permission granted enables automatic weather; Android separately controls background location permission. |
| Todos | Titles and completion state are kept in the phone app and synchronized to the glasses; glasses completion events return to the phone. Voice creation first uses cloud ASR. Todos have no account-based cloud synchronization. |
| Service Keys/Tokens | Entered by the user. Currently stored using Android Keystore and encrypted files. No maintainer-shared Key is provided. |
| Debug files and observer | May contain questions, answers, notification bodies, device identifiers, and session events. They are not redacted by default. |

Android notification access determines what notifications the app can read. “Forward only selected apps” is this project's processing rule; it does not mean Android delivers only those apps' notifications to the listener.

## User controls

The app provides controls for standby, recording, notification forwarding, and service configuration. Corresponding permissions can be revoked in system settings. **取消手机任务** (cancel phone task) cancels the associated input read, stops phone diagnostic capture, and prevents subsequent transcription or Q&A stages from continuing submission. Cancellation does not guarantee withdrawal of uploaded data or termination of tasks already accepted remotely; a provider may already have processed data and incurred charges. See [audit records](AUDIT.md) for validation scope.

The observer binds only to the computer's loopback address and reads app state over USB. It redraws protocol content and may show complete questions, answers, and notification bodies. Redact pages and logs before sharing. Bodies are not hidden by default. The observer runs for 30 minutes by default, configurable with `--seconds`; ending observation does not stop phone tasks.

Disabling automatic weather stops subsequent refreshes but does not guarantee immediate removal of the last glasses temperature or cached location. Local records have no general automatic-expiry guarantee; export recordings you need to keep. Uninstalling or clearing app data removes phone-private records and configuration, not necessarily remote-service or glasses data.

## Reporting problems

Prefer reporting the version, device/OS model, reproduction steps, and status/errors with message bodies removed. Do not upload recordings, chat content, full notifications, device addresses, serial numbers, Keys, Tokens, or configurations containing real service addresses to public Issues.

There is currently no dedicated private vulnerability reporting channel. Use an existing contact channel with the maintainer, or describe the issue category in an Issue without including sensitive details.

## Background permissions

The boot-completed receiver attempts to restore previously saved connection/standby preferences; an intentional disconnect should not automatically reconnect. Foreground services support connection, audio and background weather, subject to Android/OEM restrictions. Battery-optimization exemption and background-settings entries reduce system interruption; exemption is not data-access permission or a guarantee of uninterrupted connection. Revoke location/notification/Bluetooth access or restore battery restrictions in Android settings, or disconnect and disable weather/standby in the app.
