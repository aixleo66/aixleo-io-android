"""Independent target: compile the real decoder, never the interaction decoder double."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import lab


class ProductionDecoderFailureChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.settings = lab.settings()
        cls.temp = tempfile.TemporaryDirectory(prefix='rayneo-real-decoder-')
        cls.addClassCleanup(cls.temp.cleanup)
        cls.classes = Path(cls.temp.name) / 'classes'
        cls.classes.mkdir()
        production = [lab.ROOT / 'app/src' / name for name in (
            'RecordingDecoder.java', 'RecordingFile.java', 'OpusAudio.java', 'PcmWav.java')]
        # Integrate only these dedicated platform files under tests/decoder_stubs/.
        # Do not glob tests/stubs: it contains a same-named RecordingDecoder test double.
        platform = [lab.ROOT / 'tests/decoder_stubs' / name for name in (
            'android/os/SystemClock.java', 'android/media/MediaCodec.java',
            'android/media/MediaFormat.java', 'android/media/AudioFormat.java')]
        fixture = lab.ROOT / 'tests/RecordingDecoderFailureCheck.java'
        budget = lab.ROOT / 'tests/DecoderBudgetCheck.java'
        print(json.dumps({'decoder_test_sources': [
            {'path': str(path.relative_to(lab.ROOT)),
             'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
            for path in production + platform + [fixture]
        ]}, ensure_ascii=False))
        # These sources need no Android JAR at runtime: all used platform edges are explicit.
        lab.command([lab.tool(cls.settings, 'javac'), '-J-Duser.language=en',
                     '-encoding', 'UTF-8', '-d', cls.classes,
                     *production, *platform, fixture, budget])

    def test_budget_scales_with_length(self):
        result = lab.command([lab.tool(self.settings, 'java'), '-cp', self.classes,
                              'dev.xr.rayneo.probe.DecoderBudgetCheck'], timeout=15)
        self.assertIn(b'decoder budget checks passed', result.stdout)

    def run_scenario(self, scenario):
        with tempfile.TemporaryDirectory(prefix='rayneo-decoder-files-') as files:
            result = lab.command([
                lab.tool(self.settings, 'java'), '-cp', self.classes,
                'dev.xr.rayneo.probe.RecordingDecoderFailureCheck', scenario, files
            ], timeout=15)
            print(result.stdout.decode('utf-8'))
            self.assertIn(('Production decoder failure ' + scenario + ' passed').encode(), result.stdout)
            evidence_path = Path(files) / scenario / 'decoder-evidence.json'
            evidence = json.loads(evidence_path.read_text(encoding='utf-8'))
            self.assertFalse(evidence['returned_success'])
            self.assertEqual(evidence['raw_sha256_before'], evidence['raw_sha256_after'])
            # Every line of evidence is printed above before TemporaryDirectory removes it.
            # Use direct Java invocation with a retained directory for a frozen raw-file bundle.

    def test_real_decoder_deadline_boundary_releases_codec(self):
        self.run_scenario('deadline')

    def test_real_decoder_interrupt_exits_loop_and_releases_codec(self):
        self.run_scenario('interrupt')

    def test_existing_wav_is_not_overwritten_or_codec_created(self):
        self.run_scenario('existing-wav')

    def test_configure_failure_leaves_incomplete_derivative_and_preserves_raw(self):
        self.run_scenario('configure-failure')
