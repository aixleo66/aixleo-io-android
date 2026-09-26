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


def verify_worktree(repo):
    """Check the exact bytes distributed in a source archive without Git metadata."""
    manifest = json.loads((repo / 'source-manifest.json').read_text(encoding='utf-8'))
    entries = manifest['source_sha256']
    if not isinstance(entries, dict) or not entries:
        raise ValueError('Empty or invalid source_sha256 map')
    mismatches = []
    for name, expected in entries.items():
        path = PurePosixPath(name)
        if path.is_absolute() or '..' in path.parts or '\\' in name or ':' in name:
            raise ValueError('Invalid source path in manifest')
        file = repo.joinpath(*path.parts)
        if not file.is_file() or hashlib.sha256(file.read_bytes()).hexdigest() != expected:
            mismatches.append(name)
    return {'checked': len(entries), 'matched': len(entries) - len(mismatches),
            'mismatches': mismatches,
            'scope': 'source archive working-tree bytes; a tampered manifest itself requires an external trusted hash'}


def verify_distribution(repo):
    """Check both the manifest hashes and every file in a clean release copy."""
    result = verify_worktree(repo)
    entries = json.loads((repo / 'source-manifest.json').read_text(encoding='utf-8'))['source_sha256']
    actual = {file.relative_to(repo).as_posix() for file in repo.rglob('*')
              if file.is_file() and '.git' not in file.relative_to(repo).parts}
    result['unexpected'] = sorted(actual - set(entries) - {'source-manifest.json'})
    result['scope'] = 'clean distribution bytes and file list; excludes only Git metadata'
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--commit', default='HEAD', help='Existing local commit/ref; default HEAD')
    parser.add_argument('--worktree', action='store_true', help='Verify distributed file bytes without requiring Git')
    parser.add_argument('--distribution', action='store_true', help='Verify a clean release copy and flag extra files')
    args = parser.parse_args()
    if args.worktree and args.distribution:
        parser.error('Choose only one of --worktree and --distribution')
    try:
        repo = Path(__file__).resolve().parent
        result = verify_distribution(repo) if args.distribution else (
            verify_worktree(repo) if args.worktree else verify(repo, args.commit))
    except (ValueError, KeyError, OSError) as error:
        parser.exit(2, str(error) + '\n')
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 1 if result['mismatches'] or result.get('unexpected') else 0


if __name__ == '__main__':
    raise SystemExit(main())
