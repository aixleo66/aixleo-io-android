import json
import os
from pathlib import Path
import tempfile
import unittest
import lab

class VoiceAnswerArchiveChecks(unittest.TestCase):
    def test_real_files_round_isolation_failure_context_and_bounded_retention(self):
        s=lab.settings()
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            lab.command([lab.tool(s,'javac'),'-encoding','UTF-8','-d',root,
                         lab.ROOT/'app/src/VoiceAnswerArchive.java',lab.ROOT/'tests/VoiceAnswerArchiveCheck.java',
                         *list((lab.ROOT/'tests/stubs/org/json').glob('*.java'))])
            result=lab.command([lab.tool(s,'java'),'-cp',root,'dev.xr.rayneo.probe.VoiceAnswerArchiveCheck',root/'receipts'])
            self.assertIn(b'checks passed',result.stdout)
            values=[json.loads(p.read_text(encoding='utf-8')) for p in (root/'receipts').glob('*.json')]
            self.assertEqual(len(values),21)
            self.assertEqual(sum(v['archive_terminal'] for v in values),20)
            self.assertTrue(all('knowledge_token' not in v for v in values))
