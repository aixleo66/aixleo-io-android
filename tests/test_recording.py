import subprocess
import tempfile
import unittest
import lab

class RecordingChecks(unittest.TestCase):
    def test_offsets_duplicates_gaps_and_completion(self):
        settings = lab.settings()
        with tempfile.TemporaryDirectory(prefix='rayneo-recording-') as tmp:
            subprocess.run([str(lab.tool(settings, 'javac')), '-encoding', 'UTF-8', '-d', tmp,
                            str(lab.ROOT / 'app/src/RecordingFile.java'),
                            str(lab.ROOT / 'tests/RecordingFileCheck.java')], check=True, capture_output=True)
            result = subprocess.run([str(lab.tool(settings, 'java')), '-cp', tmp,
                                     'dev.xr.rayneo.probe.RecordingFileCheck'], check=True, capture_output=True)
            self.assertIn(b'recording checks passed', result.stdout)
