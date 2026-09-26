import tempfile
import unittest
import lab


class AsrPartialGateChecks(unittest.TestCase):
    def test_partial_pushes_match_the_official_cadence(self):
        settings = lab.settings()
        with tempfile.TemporaryDirectory() as directory:
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8', '-d', directory,
                         lab.ROOT / 'app/src/AsrPartialGate.java',
                         lab.ROOT / 'tests/AsrPartialGateCheck.java'])
            result = lab.command([lab.tool(settings, 'java'), '-cp', directory,
                                  'dev.xr.rayneo.probe.AsrPartialGateCheck'])
            self.assertIn(b'passed', result.stdout)

    def test_final_text_bypasses_the_gate(self):
        source = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        # The gate must sit behind !isFinal, or final text could be withheld from the glasses.
        self.assertIn('if (!isFinal && !partialGate.accept(value, now)) return;', source)
        # Both partial and final text move the baseline, and only after the length check.
        self.assertIn('partialGate.commit(value, now);', source)
        self.assertLess(source.index('识别文本超过问题区限制'), source.index('partialGate.commit(value, now);'))
