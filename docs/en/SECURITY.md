[简体中文](../cn/SECURITY.md) | [English](../en/SECURITY.md)

# Security mechanisms and usage boundaries

This page describes security design and retained risks on the current branch and changes when behavior changes. Affected versions, fixes, test counts, and device validation status belong in the [audit index](AUDIT.md) and historical snapshots. A design description is not proof that every version has passed validation.

## Diagnostic entry points and session isolation

- Diagnostic Activities are not exported to ordinary external apps; the main UI retains its normal launch entry point. Do not re-export diagnostics to accommodate old scripts.
- Session ownership checks restrict non-owning instances from cleaning up or writing shared state. Cleanup steps run independently, and timeout decisions are made on the main thread.
- Sample ASR diagnostics require explicit upload confirmation, a single-start guard, and coordination between stream creation/start and destruction/cancellation. User-initiated diagnostics can still call cloud services; this sample path does not open the phone microphone.
- `lab.py run --execute` rejects the legacy diagnostic launch path before installing or operating the phone. Build and install the app, then start the connection from the phone app.

Source evidence includes `app/AndroidManifest.xml`, `app/src/SdkProbeActivity.java`, `app/src/StreamSmokeActivity.java`, and related session control code. Changes to these mechanisms require checking rejected external launches, duplicate/stale instances, concurrency, and normal in-app use following the [test guide](TESTING.md), with actual results recorded.

## Retained debug capabilities

The build currently retains `debuggable=true` and is not production-hardened. User-authorized ADB `run-as` can read private app state and send commands to an existing session; this does not export diagnostic components to ordinary apps. Keystore does not eliminate in-process debugging risks, nor does this mean ordinary apps can directly decrypt every credential.

## Notifications, observer, and data

The notification listener is protected by the system `BIND_NOTIFICATION_LISTENER_SERVICE` permission. The observer binds only to loopback but may display questions, answers, notification bodies, and identifiers. Diagnostic samples, recordings, and real service configurations are not distributed with source. See [Privacy](PRIVACY.md) for data destinations and user controls.

A scan finding no specified credential patterns is evidence only within that scan's scope, not comprehensive secret detection or vendor binary security certification. New candidate directories need independent checks. Vendor redistribution authorization and file-level ownership are separate matters covered in [Licensing](LICENSING.md); a noncommercial statement does not replace authorization.

A private vulnerability reporting channel has not yet been established by the maintainer. Until one exists, do not expose usable credentials or personal data in public Issues. Reports should include the code commit, reproduction steps, and redacted evidence.

Android documentation: [Exported component risks](https://developer.android.com/privacy-and-security/risks/android-exported) and [Debuggable app risks](https://developer.android.com/privacy-and-security/risks/android-debuggable).
