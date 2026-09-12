[简体中文](../cn/AUDIT.md) | [English](AUDIT.md)

# Independent audit and 0.13 fix status · 2026-09-12

The current version is the **0.13 source preview**. Diagnostic entry-point isolation and session ownership fixes for SEC-01/02 have been implemented locally. Independent source review, 45 automated tests, and build checks have passed. Full device regression testing remains incomplete, and SEC-03 debug capabilities remain. The Git repository contains only source, pinned vendor dependencies, documentation, and build/test tools; no APK is included and no GitHub Release is being created. The 0.12-rc1/rc2 audits, hashes, and 42-test results below are historical and do not establish validation of 0.13.

## Completed and pending 0.13 validation

- All 45 automated tests passed, including external entry-point declarations, duplicate/stale session ownership, concurrent contention, and rejection of the old diagnostic CLI before device access. No cloud calls were made.
- An independent security agent reviewed the SEC-01/02 fixes: non-exported diagnostic pages, ownership checks, independent cleanup steps, timeout decisions on the main thread, and ASR explicit confirmation/destruction cancellation coordination. No new blocking issue was found within the scope of these fixes.
- The local 0.13 build succeeded. Signature and alignment checks passed, and the vendor payload retained its pinned hash. In the actual APK, all four diagnostic Activities have `exported=false`; the main UI and system-permission-protected notification service retain their expected declarations.
- This version contains new code and does not reuse the 0.12 APK identity. The locally built APK has SHA256 `09b1c817cd2885626f63c6fbb405f64f250a3d39507b9cc75190ab626bebbd9e`. That APK is not included in the source repository.
- No ADB device was connected at the time of these checks. Real-device verification of rejected external launches and regression tests for connection, consecutive voice turns, screen-off behavior, recording, and notifications remain pending. This cannot be called complete validation. The upload directory's file inventory and specified credential-pattern scans were checked with no matches; this is not a comprehensive secret-detection guarantee.

An independent rebuild of the source snapshot also passed, using a newly generated temporary signing key and external configuration. `classes.dex`, the Manifest, and the vendor payload were byte-identical to the final local 0.13 build. Signature and alignment checks passed. The source snapshot excludes the audit-generated `out` directory, APK, configuration, and private key. See [source-manifest.json](../../source-manifest.json).

## Historical audit target

The original target was local research candidate 0.12-rc1. Three independent subagents reviewed completeness/builds, documentation/workflows, and security/provenance/licensing. They did not install the app on a phone, trigger a real device, or call cloud services. The main agent then prepared rc2 documentation and tooling revisions while preserving rc1 and its test identity.

## Historical 0.12 findings and disposition

| ID | Severity | Finding | rc2 status |
| --- | --- | --- | --- |
| SEC-01 | P1 | Incorrect cleanup through an unprotected diagnostic entry point could interfere with the existing connection service and state. | Unfixed in rc2; see SECURITY.md for 0.13 status. |
| SEC-02 | P2 | An external app could trigger ASR when a diagnostic sample and configured Key were present. | Unfixed; necessary preconditions documented. |
| SEC-03 | P2 | The debug APK was not production-hardened. | Research baseline retained; unfixed. |
| DOC-01 | P2 | Missing instructions for the initial device address, real-time switch, and notification listen-only default. | Added to BUILDING.md. |
| PKG-01 | P2 | `stream_smoke.py` depended on private configuration/samples and an undeclared third-party library; direct execution called the cloud. | Removed from the candidate package; the internal historical file was not distributed. |
| DOC-02 | P2 | The phone's preset speech button lacked a sample on a fresh install. | Documented as unavailable, with an alternative entry point for user-supplied audio; the APK button was unchanged. |
| DOC-03 | P2 | Missing observer instructions, test reproduction steps, and evidence boundaries. | Added OBSERVER.md and TESTING.md. |
| DOC-04 | P2 | Compatibility requirements for a self-hosted knowledge-base Gateway were unclear. | Added protocol documentation and cancellation/disconnection limits. |
| DOC-05 | P3 | Unclear status of historical undistributed source files and included licenses. | Revised PROVENANCE.md and THIRD_PARTY_NOTICES.md. |
| PKG-02 | P3 | Bootstrap/help text retained private NAS references and a same-signature requirement. | Revised help text only. |

## Historical independent 0.12 build results

The rc1 ZIP CRC, external SHA256, and per-file hashes inside the package all passed. A new temporary debug signing key was generated in an independently extracted directory. Building with the existing pinned JDK/SDK succeeded; signature and alignment validation passed without the original private key. All 42 tests passed.

The rebuilt `classes.dex`, `assets/vendor-payload.jar`, and `AndroidManifest.xml` were byte-identical to the original tested APK. The APK included in that historical package retained SHA256 `14a3125722f60ff2dc514a10c695f77ba0f11b9a1c8e9f2d1f11a1831924eae2`. The toolchain was not downloaded again on a fresh Windows installation, and all hardware tests were not repeated.

At that time, rc2 did not change App Java/Manifest files or the APK. It revised public documentation and tool messages and excluded an incomplete historical tool. Its `release-manifest.json` and `SHA256SUMS.txt` remain in the local historical candidate package; they are not build evidence for the current source preview. Repackaging did not fix the original APK's security issues.

A focused independent rc2 review also passed: 34 App source/Manifest/APK/payload files were unchanged from rc1, and the build script changed only two messages. Another build using configuration outside the staging directory and a new auditor-generated signing key produced byte-identical versions of the three key artifacts, with passing signature/alignment checks. The 42 tests were not rerun in that round. The documentation and security notes were reviewed by the respective audit agents.

## Ownership and public-distribution boundaries

Turbo IO references and licensing are identified, original WebSocket/SLF4J license texts are included, and the vendor DEX provenance/version and hashes are recorded. Vendor redistribution authorization and file-level ownership checks remain incomplete. Noncommercial or research use, and upstream's inclusion of binaries, do not independently prove that we have redistribution authorization.

Historical specified credential-pattern scans found no leaks; they were not comprehensive secret or binary security audits. The 0.13 SEC-01/02 fixes and source/build checks described above are complete, while device regression testing remains pending. The current source preview includes neither old candidate packages nor installable packages. Actual Git upload status is recorded separately; no GitHub Release is being created.
