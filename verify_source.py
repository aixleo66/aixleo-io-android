"""Verify a committed source manifest against Git blob bytes, independent of checkout EOLs."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import subprocess


def git_bytes(repo, *arguments):
    result = subprocess.run(['git', *arguments], cwd=repo, capture_output=True, check=False)
    if result.returncode:
        raise ValueError('Git object unavailable; use an existing commit in a local Git checkout')
    return result.stdout


def verify(repo, revision='HEAD'):
    commit = git_bytes(repo, 'rev-parse', '--verify', '--end-of-options', revision + '^{commit}').decode('ascii').strip()
    manifest = json.loads(git_bytes(repo, 'show', commit + ':source-manifest.json'))
    entries = manifest['source_sha256']
    if not isinstance(entries, dict) or not entries:
        raise ValueError('Empty or invalid source_sha256 map')
    mismatches = []
    for name, expected in entries.items():
        path = PurePosixPath(name)
        if path.is_absolute() or '..' in path.parts or '\\' in name or ':' in name:
            raise ValueError('Invalid source path in manifest')
        blob = git_bytes(repo, 'show', commit + ':' + name)
        if hashlib.sha256(blob).hexdigest() != expected:
            mismatches.append(name)
    return {'commit': commit, 'checked': len(entries), 'matched': len(entries) - len(mismatches),
            'mismatches': mismatches, 'scope': 'committed Git blob bytes; working-tree edits and EOL conversion are not checked'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--commit', default='HEAD', help='Existing local commit/ref; default HEAD')
    args = parser.parse_args()
    try:
        result = verify(Path(__file__).resolve().parent, args.commit)
    except (ValueError, KeyError, OSError) as error:
        parser.exit(2, str(error) + '\n')
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 1 if result['mismatches'] else 0


if __name__ == '__main__':
    raise SystemExit(main())
