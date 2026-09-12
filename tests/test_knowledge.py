import tempfile
import unittest
import lab

class KnowledgeChecks(unittest.TestCase):
    def test_run_correlation_completion_and_replay(self):
        settings = lab.settings()
        with tempfile.TemporaryDirectory() as directory:
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8', '-d', directory,
                         lab.ROOT / 'app/src/KnowledgeRunState.java', lab.ROOT / 'tests/KnowledgeRunStateCheck.java'])
            result = lab.command([lab.tool(settings, 'java'), '-cp', directory, 'dev.xr.rayneo.probe.KnowledgeRunStateCheck'])
            self.assertIn(b'knowledge run checks passed', result.stdout)
