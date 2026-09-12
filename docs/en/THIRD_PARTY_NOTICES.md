[简体中文](../cn/THIRD_PARTY_NOTICES.md) | [English](THIRD_PARTY_NOTICES.md)

# Third-party components and provenance notices

This page lists the third-party components, their sources, and licenses. See [Licensing](LICENSING.md) for the applicable scope.

Thanks to the author and contributors of [Turbo1123/Turbo-IO](https://github.com/Turbo1123/Turbo-IO). See [Provenance](PROVENANCE.md) for the reference relationships.

| Component / source | Current use | License and repository contents |
| --- | --- | --- |
| Java-WebSocket 1.5.6 | WebSocket connections for real-time ASR and Gateway | MIT; preserve upstream copyright and license text. Dependency sources and hashes are recorded in the local inventory. |
| slf4j-api / slf4j-nop 2.0.6 | WebSocket logging interface and no-op implementation | MIT; preserve upstream copyright and license text. |
| Vendor communication code from the official RayNeo app | Device connection, authentication, and message exchange in the research build | The repository includes three unmodified DEX files and two coroutine service declarations, packaged as `vendor-payload.jar`. A locally built APK contains this dependency; no APK is included in the repository. Source version: 1.0.2 (68); hashes are in the vendor inventory. Vendor redistribution rights have not been verified, and the root license does not cover this material. |
| Turbo IO | Reference for workflows, protocol/test vectors, and parts of the implementation | The upstream license text is in third-party/Turbo-IO/LICENSE. File-level reference and port scope is still under review; see Provenance. |
| Android SDK / JDK / Python and other tools | Compilation and development | Developers obtain them under their respective licenses. Private caches, tool installers, and signing material are not distributed. |

Turbo IO currently licenses its original portions under PolyForm Noncommercial 1.0.0 and preserves rights already granted by historical MIT versions in its notices. Third-party components such as vendor frameworks are not relicensed by its root license. See [upstream licensing](https://github.com/Turbo1123/Turbo-IO/blob/main/docs/LICENSING.md) and [upstream third-party notices](https://github.com/Turbo1123/Turbo-IO/blob/main/THIRD_PARTY_NOTICES.md).

Protected content copied, translated, or adapted from upstream requires checking and complying with its actual applicable license. Similar functionality, facts needed for compatibility, and independent implementations must be assessed separately using provenance records. A file extension or programming language alone does not determine the relationship.

Vendor names identify interoperability targets only. The maintainer does not represent the vendor and grants no additional rights to vendor software, interface material, trademarks, or services.

Already included: original Java-WebSocket and SLF4J license texts in `app/lib`, and `third-party/Turbo-IO/LICENSE`. The build also places the first two licenses in the generated APK's assets. A complete file-level provenance table, specific copyright notices for ported content, and applicable vendor dependency terms remain to be completed.
