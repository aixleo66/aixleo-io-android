import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import verify_source


class SourceManifestChecks(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='rayneo-git-hash-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git('init', '-q')
        self.git('config', 'core.autocrlf', 'false')
        self.original = b'class Example {}\n'
        (self.root / 'example.java').write_bytes(self.original)
        (self.root / 'source-manifest.json').write_text(json.dumps({'source_sha256': {
            'example.java': hashlib.sha256(self.original).hexdigest()}}), encoding='utf-8')
        self.commit()

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.root, stderr=subprocess.PIPE)

    def commit(self):
        self.git('add', '.')
        self.git('-c', 'user.name=Local Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'fixture')
        return self.git('rev-parse', 'HEAD').decode().strip()

    def test_crlf_checkout_and_local_manifest_edits_do_not_change_blob_verification(self):
        (self.root / 'example.java').write_bytes(self.original.replace(b'\n', b'\r\n'))
        (self.root / 'source-manifest.json').write_text('not committed', encoding='utf-8')
        self.assertEqual(verify_source.verify(self.root)['matched'], 1)

    def test_committed_content_change_without_manifest_update_is_detected(self):
        (self.root / 'example.java').write_bytes(b'changed\n')
        self.commit()
        self.assertEqual(verify_source.verify(self.root)['mismatches'], ['example.java'])

    def test_explicit_historical_commit_uses_its_own_manifest(self):
        old = self.git('rev-parse', 'HEAD').decode().strip()
        (self.root / 'example.java').write_bytes(b'changed\n')
        self.commit()
        self.assertEqual(verify_source.verify(self.root, old)['matched'], 1)

    def test_missing_commit_is_reported_without_network_access(self):
        with self.assertRaises(ValueError):
            verify_source.verify(self.root, 'missing-local-ref')
