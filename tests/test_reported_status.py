import tempfile
import unittest
import lab


class ReportedStatusChecks(unittest.TestCase):
    def test_scalar_state_preserves_zero_without_private_or_unknown_values(self):
        settings = lab.settings()
        with tempfile.TemporaryDirectory() as directory:
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8', '-d', directory,
                         lab.ROOT / 'app/src/ReportedStatusPolicy.java',
                         lab.ROOT / 'tests/ReportedStatusPolicyCheck.java'])
            result = lab.command([lab.tool(settings, 'java'), '-cp', directory,
                                  'dev.xr.rayneo.probe.ReportedStatusPolicyCheck'])
            self.assertIn(b'checks passed', result.stdout)
