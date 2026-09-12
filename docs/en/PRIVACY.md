[简体中文](../cn/PRIVACY.md) | [English](PRIVACY.md)

# Permissions, data, and diagnostics

This document describes data flows on the current branch and should be read alongside the code commit you use. Isolating diagnostic entry points does not change where data goes during user-initiated recording, transcription, or Q&A. Debug builds and user-authorized ADB state reads remain available. See [SECURITY](SECURITY.md) for implemented protections and validation limits. Update this document whenever a version changes data flows; planned privacy improvements must not be presented as existing guarantees.

| Data or capability | Current use and destination |
| --- | --- |
| Bluetooth scanning and connection | Discovers and connects to glasses selected by the user, receives device events, audio, and state, and sends display/control messages. |
| Phone microphone | Used only when the user explicitly selects a phone microphone test. Normal glasses voice interaction uses the glasses microphone. A future release could isolate this diagnostic capability separately. |
| Ordinary glasses recording | Raw audio, acknowledgements, and derived WAV files are saved in the phone app's private directory. Saving does not automatically call cloud transcription. |
| Voice assistant audio | Sent to the user-configured ASR service after a voice turn is triggered. Real-time mode uploads while receiving audio; accidental wake-ups can therefore cause requests. |
| Questions and answers | Valid questions are sent to the selected model or remote Gateway. The phone retains recent results and sends answers to the glasses. Remote retention depends on the chosen service. |
| Phone notifications | After system notification access is granted, local code filters notifications by app/category. Selected content is sent to the glasses, not to a model. |
| Service Keys/Tokens | Entered by the user. Currently stored using Android Keystore and encrypted files. No maintainer-shared Key is provided. |
| Debug files and observer | May contain questions, answers, notification bodies, device identifiers, and session events. They are not redacted by default. |

Android notification access determines what notifications the app can read. “Forward only selected apps” is this project's processing rule; it does not mean Android delivers only those apps' notifications to the listener.

## User controls

The app provides controls for standby, recording, notification forwarding, and service configuration. Corresponding permissions can be revoked in system settings. **取消手机任务** (cancel phone task) cancels the associated input read, stops phone diagnostic capture, and prevents subsequent transcription or Q&A stages from continuing submission. Cancellation does not guarantee withdrawal of uploaded data or termination of tasks already accepted remotely; a provider may already have processed data and incurred charges. See [audit records](AUDIT.md) for validation scope.

The observer binds only to the computer's loopback address and reads this app's state over USB. The web page redraws protocol content; it is not a screenshot of the lenses. It can still expose message bodies. Hiding bodies by default, requiring explicit opt-in, and limiting observation duration are planned public-version improvements, not completed changes.

## Reporting problems

Prefer reporting the version, device/OS model, reproduction steps, and status/errors with message bodies removed. Do not upload recordings, chat content, full notifications, device addresses, serial numbers, Keys, Tokens, or configurations containing real service addresses to public Issues.

A private vulnerability reporting channel has not yet been established by the maintainer. Until one is available, do not publicly submit usable credentials or personal data. Before a release, verify the actual recording/configuration deletion and export interactions, then document the behavior that has been confirmed.
