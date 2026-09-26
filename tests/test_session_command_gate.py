import tempfile
import unittest
import lab


class CommandGateChecks(unittest.TestCase):
    def test_ordered_admission_and_cleanup_matrix(self):
        settings = lab.settings()
        with tempfile.TemporaryDirectory() as tmp:
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8', '-d', tmp,
                         lab.ROOT / 'app/src/ConnectionReadiness.java',
                         lab.ROOT / 'app/src/SessionCommandGate.java',
                         lab.ROOT / 'tests/SessionCommandGateCheck.java'])
            result = lab.command([lab.tool(settings, 'java'), '-cp', tmp,
                                  'dev.xr.rayneo.probe.SessionCommandGateCheck'])
            print(result.stdout.decode('utf-8'))
            self.assertIn(b'22 scenarios x 10 repeats passed', result.stdout)
