[简体中文](../cn/SECURITY.md) | [English](../en/SECURITY.md)

# Security mechanisms and usage boundaries

This page covers diagnostic entry points, session isolation, debug access, and data protection. See [test and audit records](AUDIT.md) for specific defects and fixes.

## Diagnostic entry points and session isolation

- Diagnostic Activities are not exported to ordinary external apps; the main UI retains its normal launch entry point. Do not re-export diagnostics to accommodate old scripts.
- Session ownership checks restrict non-owning instances from cleaning up or writing shared state. Cleanup steps run independently, and timeout decisions are made on the main thread.
- Sample ASR diagnostics require explicit upload confirmation, a single-start guard, and coordination between stream creation/start and destruction/cancellation. User-initiated diagnostics can still call cloud services; this sample path does not open the phone microphone.
- `lab.py run --execute` rejects the legacy diagnostic launch path before installing or operating the phone. Build and install the app, then start the connection from the phone app.

The implementation is in `app/AndroidManifest.xml`, `app/src/SdkProbeActivity.java`, `app/src/StreamSmokeActivity.java`, and related session control code. See the [test guide](TESTING.md) for regression procedures.

## Retained debug capabilities

The build currently retains `debuggable=true` and is not production-hardened. User-authorized ADB `run-as` can read private app state and send commands to an existing session; this does not export diagnostic components to ordinary apps. Keystore does not eliminate in-process debugging risks, nor does this mean ordinary apps can directly decrypt every credential.

## Notifications, observer, and data

The notification listener is protected by the system `BIND_NOTIFICATION_LISTENER_SERVICE` permission. The observer binds only to loopback but may display questions, answers, notification bodies, and identifiers. Diagnostic samples, recordings, and real service configurations are not distributed with source. See [Privacy](PRIVACY.md) for data destinations and user controls.

Credential scan scopes and results are listed in [audit records](AUDIT.md). Pattern scans cannot guarantee detection of every secret and are not a comprehensive security audit of vendor binaries.

There is currently no dedicated private vulnerability reporting channel. Use an existing contact channel with the maintainer, or describe the issue category in an Issue without exposing usable credentials, personal data, or sensitive exploit details.

Android documentation: [Exported component risks](https://developer.android.com/privacy-and-security/risks/android-exported) and [Debuggable app risks](https://developer.android.com/privacy-and-security/risks/android-debuggable).
