[简体中文](../cn/DOCUMENTATION.md) | [English](../en/DOCUMENTATION.md)

# Documentation maintenance

Default documentation follows the current branch. When using an older commit, read the documentation at that commit. General requirements do not need a version-number replacement on every upgrade, but are not promised to remain unchanged forever. Update relevant pages and explain upgrade effects when features, interfaces, defaults, permissions, dependencies, or data flows change.

| Document | Responsibility | Update trigger |
| --- | --- | --- |
| README, building, observer, Gateway, privacy, and other guides | Purpose, behavior, architectural boundaries, and procedures for current code | Relevant behavior changes; not an app version-number bump alone |
| CHANGELOG | Additions, fixes, behavior changes, compatibility, and upgrade notes | Record changes under Unreleased first, then group them under the corresponding version at delivery |
| AUDIT index and `audits/date-version.md` | Exact versions/commits, artifact hashes, environments, completed and pending checks | Add a record after new audits/tests, then update the index |
| COMPATIBILITY | Real coverage scoped to versions, devices, systems, and conditions | New coverage or changed compatibility; retain historical scope |
| SECURITY, licensing, and third-party notices | Security design, retained risks, rights, and dependency provenance | Changes to mechanisms, risks, dependencies, provenance, or licensing facts |
| RELEASE-CHECKLIST | Distribution content and reusable delivery procedures | Distribution scope or process changes |

## Versions and evidence

- Do not replace an audit's version, hash, test count, or date with those of a new version. Create a new record for new conclusions. Date and explain historical corrections.
- A fix may explicitly identify the first fixed version or affected versions. These version references carry useful meaning and should remain.
- JDK, Android SDK, vendor dependency, protocol, and license versions convey compatibility or rights information. They are not app-version prose to remove.
- New code on the current branch is not automatically covered by the latest audit. Mark uncovered behavior as pending validation.
- Each source snapshot gets its own `source-manifest.json`. Historical audits link to the corresponding commit's manifest so later manifests do not replace their evidence.

## Verifying source hashes

`source-manifest.json` records byte hashes for source, documentation, configuration templates, and bundled dependencies, excluding the manifest itself. Verify committed bytes in a Git checkout, or extracted bytes in a source archive:

```powershell
python verify_source.py --commit HEAD
python verify_source.py --worktree
python verify_source.py --distribution
```

Replace HEAD with a specific local commit if needed. `--worktree` works for an archive without `.git`; `--distribution` also flags unlisted files and should run on a freshly extracted copy before generating local config or build output. These modes use no network, cloud, or device. Windows `core.autocrlf` can change working-tree line endings, so Git and worktree checks can differ; check the release format before interpreting a mismatch. The manifest itself needs a trusted Git commit or external digest. Vendor payload entries and build artifacts have separate build checks.

## Chinese and English parity rules

Keep matching relative paths under `docs/cn` and `docs/en` as a rule. When behavior changes, update both corresponding pages and keep language switches, examples, limitations, and test status aligned. The feature matrix, device-capability overview and troubleshooting guide all have full Chinese and English versions; content links should prefer the matching language. The root README defaults to Chinese. Do not translate original third-party license texts.

## Each version iteration

Normally, update the changelog, affected guides, applicable test records, and audit/compatibility indexes. Unchanged pages need no rewrite. The project does not copy its entire documentation for every minor version; use Git commits to read older documentation. If multiple incompatible versions are maintained later, consider release branches or a versioned documentation site.

References: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) centralizes unreleased changes; [Django's release process](https://docs.djangoproject.com/en/4.2/internals/release-process/) distinguishes maintenance of development and stable-version documentation. This project adopts a simpler process appropriate to its current size.

## Audience and tone

The README and user guides should lead with the purpose, features, procedures, and limitations that affect use. Keep licensing scope in licensing and third-party notices, test evidence in audit records, and release procedures in the maintainer checklist. Keep internal handoff instructions, approval steps, and repeated audit statements out of the project introduction.
