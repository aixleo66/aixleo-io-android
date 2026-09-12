[简体中文](../cn/CHANGELOG.md) | [English](../en/CHANGELOG.md)

# Changelog

Records changes relevant to users. A source-preview version is not a published GitHub Release. See the [audit index](AUDIT.md) for validation evidence and [documentation maintenance](DOCUMENTATION.md) for the process.

## Unreleased

### Documentation

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
