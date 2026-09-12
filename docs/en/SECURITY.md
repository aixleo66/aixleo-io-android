[简体中文](../cn/SECURITY.md) | [English](SECURITY.md)

# Security status of the 0.13 source preview

An independent audit of the local 0.12 research candidate was conducted on September 12, 2026. The SEC-01/02 fixes below have been implemented locally in 0.13. Independent source review, 45 automated tests, and build checks have passed; full device regression testing is still pending. This repository contains source, pinned vendor dependencies, documentation, and build/test tools. It includes neither the old 0.12 APK nor a new APK, and no GitHub Release is being created.

| ID | Historical risk | Implementation and current status in 0.13 |
| --- | --- | --- |
| SEC-01 | P1: In 0.12, `SdkProbeActivity` was exported without a permission guard. An incorrect instance could clean up the existing connection service and overwrite state. | Diagnostic Activities are no longer exported. Session ownership checks prevent non-owning instances from cleaning up or writing shared state. Independent source review, 45 automated tests, and build checks have passed. These code changes do not establish that every lifecycle case has passed on a real device. |
| SEC-02 | P2: In 0.12, `StreamSmokeActivity` was exported without a permission guard. An external app could trigger ASR if a valid diagnostic sample had been left behind and a Key was configured. This path did not open the phone microphone. | The Activity is no longer exported, blocking ordinary external apps from launching this diagnostic path. Upload confirmation and a single-start guard were added, with the same lock coordinating destruction and stream creation/start. Independent source review, 45 automated tests, and build checks have passed. User-initiated in-app diagnostics can still call cloud services; not all cloud diagnostics have been removed. |
| SEC-03 | P2: A debug build is not a production-hardened build. | `debuggable=true` remains enabled. Authorized debugging access can read private app state. Keystore does not eliminate debugging risks inside the running process; this does not mean ordinary apps can directly decrypt every credential. |

Source evidence includes the diagnostic component export settings in `app/AndroidManifest.xml`, session ownership and cleanup paths in `app/src/SdkProbeActivity.java`, and related session control code. See [AUDIT](AUDIT.md) for audit history and validation progress. The 0.12 test APK is historical local evidence; its hash and device results do not represent 0.13.

In 0.13, `lab.py run --execute` rejects the legacy diagnostic launch path before installing or operating the phone because those components are no longer exported. Build and install the app, then launch it on the phone. Do not re-export components to accommodate old scripts. The debug build still supports user-authorized ADB `run-as` state reads and existing-session commands; this does not expose those components to ordinary apps.

The notification listener service is protected by the system `BIND_NOTIFICATION_LISTENER_SERVICE` permission. The observer binds only to loopback, but may show questions, answers, notification bodies, and identifiers; see [Privacy](PRIVACY.md). Diagnostic samples, recordings, and service configurations are not distributed with the source.

Specified credential-pattern scans of historical candidates found no matches. That is not a comprehensive secret scan or a security certification of vendor binaries; a new source directory requires its own checks. Vendor redistribution authorization and file-level ownership review are separate matters described in [Licensing](LICENSING.md). A noncommercial statement does not replace authorization.

The compiled artifact's Manifest and pure-logic session isolation checks have passed. Real-device confirmation that external launches are rejected, along with in-app connection, voice, recording, and notification regression tests, is still pending. This is not labeled a production-hardened version. Update the audit record with actual results when testing is completed, rather than carrying forward the old version's identity.

Android documentation: [Exported component risks](https://developer.android.com/privacy-and-security/risks/android-exported) and [Debuggable app risks](https://developer.android.com/privacy-and-security/risks/android-debuggable).
