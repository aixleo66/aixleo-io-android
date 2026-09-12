[简体中文](../cn/AUDIT.md) | [English](../en/AUDIT.md)

# Audit and validation records

This page centralizes validation status for specific code versions. Guides describe behavior on the current branch; a passing result applies only to the version, commit, artifact, and environment explicitly identified in its record. It does not automatically cover later commits.

## Most recent code audit

| Target | Record date | Completed | Pending / retained limits | Evidence |
| --- | --- | --- | --- | --- |
| 0.13 source preview | 2026-09-12 | Independent SEC-01/02 source review, 45 automated tests, build, signature/alignment checks, and independent snapshot rebuild | Full device regression; SEC-03 debug capabilities retained | [0.13 audit snapshot](audits/2026-09-12-0.13.md) |

This table identifies the most recent recorded code audit; it does not declare every subsequent commit on main accepted. Documentation revisions add no device or cloud test results. See [compatibility records](COMPATIBILITY.md) for device coverage and the [test guide](TESTING.md) for reusable procedures.

## Historical records

- [2026-09-12: 0.13 fixes and validation, including historical 0.12-rc1/rc2 audits](audits/2026-09-12-0.13.md). Artifact hashes, test counts, pending work at that time, and third-party rights boundaries remain in that record.

Create a dated, version-specific record for new testing, then update this index. Do not bulk-replace version numbers or pending statuses in old records. Date and explain corrections to historical errors. See [documentation maintenance](DOCUMENTATION.md) for the rules and the [changelog](CHANGELOG.md) for feature differences.
