[简体中文](../cn/RELEASE-CHECKLIST.md) | [English](../en/RELEASE-CHECKLIST.md)

# Release checklist for maintainers

This page is for maintainers preparing version updates. For installation and configuration, see [Building](BUILDING.md); for validation results, see [test records](AUDIT.md).

## Current source distribution scope

Includes original app/adapter source, tests, build scripts, empty configuration templates, and the pinned `vendor-payload.jar` (three official DEX files and two coroutine service declarations), Java-WebSocket/SLF4J dependencies and licenses, Turbo IO references and provenance, and the original PolyForm license text. Guides cover initial setup, observer use, regression testing, the experimental Gateway, and compatibility.

Excludes prebuilt APKs, installable packages under `artifacts/`, the complete official APK, private signing material, Keys/Tokens, real local configurations, recordings, real questions/answers and notifications, unique device identifiers, personal knowledge bases, the Rokid Harness server, raw captures/full decompilation output, tool caches, and the historical `stream_smoke.py` that depended on private samples.

Only source and build instructions are currently available; there is no downloadable prebuilt APK. Future packages and version announcements will be described in the [changelog](CHANGELOG.md).

## Checks for each delivery

1. Identify the commit, distribution inventory, and version differences; update the [changelog](CHANGELOG.md). Change only the Chinese and English guides affected by behavior changes.
2. Check source files and nested payloads for specified credential patterns, exclusions, original license texts, provenance notices, and local links.
3. Verify pinned toolchain/vendor versions and hashes. Rerun applicable builds and tests when code or build inputs change. New snapshots need their own manifests; do not rewrite old snapshot hashes.
4. Record automated checks, device results, and uncovered cases. Documentation-only changes can be checked for links, examples, and factual consistency, but cannot add device acceptance results. See the [test guide](TESTING.md).
5. Update the [audit index](AUDIT.md) and retain historical evidence. Record completion only after checking the actual push or publication outcome; do not mark pending work complete.

Related references: [Security](SECURITY.md), [licensing scope and unresolved items](LICENSING.md), and [documentation maintenance](DOCUMENTATION.md).
