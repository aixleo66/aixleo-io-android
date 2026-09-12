[简体中文](../cn/CHANGELOG.md) | [English](../en/CHANGELOG.md)

# Changelog

Records changes relevant to users. A source-preview version is not a published GitHub Release. See the [audit index](AUDIT.md) for validation evidence and [documentation maintenance](DOCUMENTATION.md) for the process.

## Unreleased

- Renamed the project to **Aixleo iO Android** and the repository to `aixleo-io-android`, preserving commit history.
- Updated the app display name, overview titles, and repository links. Device names, Turbo-IO attribution, vendor provenance, and licensing notices are retained.
- The Android application ID, signing mechanism, and storage paths are unchanged. This change has not been installed on a phone and adds no device validation claims.

- After renaming, all 59 existing automated tests and build/signature/alignment checks passed; the source manifest was updated.

## 0.14 interaction-fix source preview — 2026-09-12

### Fixed

- F1: File/preset audio and phone fallback diagnostics share the current cancellation object. Cancellation during input, ASR, or Q&A prevents continuation to the next stage.
- F2: Disconnect can interrupt busy/pending operations. Old command wait callbacks are invalidated to prevent stale UI updates.
- F3: Device-initiated and local stops share a completion deadline. Missing completion reports fail the task while preserving raw data; repeated stops do not extend the deadline.
- F4: Phone display, automatic delivery, and manual delivery share the current answer, avoiding stale cached answers.

### Documentation and validation

- F5: Clarify Android 12+ / API 31 for glasses connection, while the installation declaration remains API 29. No lower-version support was added.
- D1: Add `verify_source.py` to check a commit's manifest against Git blobs, unaffected by working-tree EOL conversion.
- The README explains that core workflows were demonstrated previously while interaction details remain under focused testing and iteration.
- 59 automated tests and build checks passed; focused device acceptance and external independent review of the new code are pending. See [validation records](audits/2026-09-12-0.14.md). No APK is included and no GitHub Release is created.

## Earlier documentation updates

### Documentation

- Describe the vibe coding development approach in collaboration with Codex and provide a feedback entry point.
- Provide Chinese and English documentation in `docs/cn` and `docs/en`, with Chinese as the default and legacy entry links preserved.
- Separate reusable guides from version acceptance; archive dated audits and centralize the validation index.
- Clarify that Gateway includes only the experimental Android adapter, without the Rokid Harness server.
- These documentation changes do not alter app code, pinned dependencies, or original license texts, and add no device acceptance results.

## 0.13 source preview — 2026-09-12

### Fixed

- Isolate diagnostic Activity entry points from external apps and add session ownership and cleanup guards.
- Add upload confirmation, a single-start guard, and destruction coordination to sample ASR diagnostics. The legacy diagnostic CLI rejects unsupported launches before operating the phone.

### Validation and limits

- See the [audit snapshot](audits/2026-09-12-0.13.md) for automated checks, build results, and device testing pending for the corresponding commit. Debug capabilities remain, no APK is included, and no GitHub Release was created.

## 0.12 historical research baseline

- See [compatibility records](COMPATIBILITY.md) for historical glasses voice, recording, notification, and other end-to-end results, and the [historical snapshot](audits/2026-09-12-0.13.md) for rc1/rc2 audits and artifact identities. These are local research records, not a public installable package or acceptance of the current version.
