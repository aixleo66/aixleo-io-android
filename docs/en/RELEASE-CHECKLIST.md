[简体中文](../cn/RELEASE-CHECKLIST.md) | [English](RELEASE-CHECKLIST.md)

# Scope of the 0.13 source preview

This Git repository provides source, pinned vendor dependencies, documentation, and build/test tools. No GitHub Release or installable package is being published in this round. The purpose is noncommercial research and exploration of the device's possibilities. Uploading source and publishing an APK are separate actions; this document does not preemptively mark an upload as completed.

## Included

- Original 0.13 app/adapter source, local SEC-01/02 fixes, pure-logic tests, build scripts, and empty configuration templates.
- `vendor-payload.jar`: three official DEX files and two coroutine service declarations, unchanged from the historical dependency baseline.
- Java-WebSocket/SLF4J dependencies and licenses, Turbo IO attribution and provenance, and the original PolyForm noncommercial license text.
- Initial setup, observer, test reproduction, Gateway contract, and compatibility documentation.
- Explicit separation of security findings, implemented fixes, validation progress, and historical test results.

## Excluded

Installable packages under `artifacts/`, 0.12/0.13 APKs, the complete official APK, private signing material, Keys/Tokens, local configurations, recordings, real questions/answers and notifications, unique device identifiers, personal knowledge bases, raw packet captures/full decompilation output, tool caches, and the historical `stream_smoke.py` that depended on private samples.

## Current boundaries

SEC-01/02 fixes have been implemented locally; independent source review, 45 automated tests, and build checks have passed. SEC-03 debug capabilities remain. Full device regression testing is incomplete. See [SECURITY](SECURITY.md) and [AUDIT](AUDIT.md). The complete 0.12 device workflow cannot be treated as acceptance testing for 0.13.

Vendor redistribution authorization and a complete file-level ownership review remain unfinished. Choosing to bundle vendor dependencies with source does not grant vendor rights under the root license. Retaining iOS attribution also does not replace license compliance.

The upload directory must be checked independently for its file inventory, credential patterns inside nested payloads, licenses and provenance notices, local links, and build inputs. Record actual check and upload results in the audit record; do not mark pending work as completed.
