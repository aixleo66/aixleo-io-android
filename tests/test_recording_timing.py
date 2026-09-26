import json
import os
from pathlib import Path
import tempfile
import unittest
import lab


class RecordingTimingChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.settings = lab.settings()
        cls.temp = tempfile.TemporaryDirectory(prefix='recording-timing-')
        cls.addClassCleanup(cls.temp.cleanup)
        cls.directory = Path(cls.temp.name)
        source = (lab.ROOT / 'app/src/GlassesRecorder.java').read_text(encoding='utf-8')
        # Windows renameTo cannot replace an existing file. Model Android's replacement
        # here only; actual fsync/atomicity/MediaCodec still require device evidence.
        original = 'tmp.renameTo(new File(folder,"receipt.json"))'
        if source.count(original) != 1:
            raise AssertionError('Recorder receipt replacement seam changed')
        adapted = source.replace(original, '(java.nio.file.Files.move(tmp.toPath(),new File(folder,"receipt.json").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)!=null)')
        recorder = cls.directory / 'GlassesRecorder.java'
        recorder.write_text(adapted, encoding='utf-8')
        cls.dependencies = os.pathsep.join(map(str, [cls.settings['android_jar'], *list((lab.ROOT / 'app/lib').glob('*.jar'))]))
        names = ('RecordingTimeline.java', 'RecordingProgress.java', 'RecordingFile.java', 'RecordingMarkStore.java', 'BusinessEnvelope.java', 'OggOpusWriter.java')
        lab.command([lab.tool(cls.settings, 'javac'), '-encoding', 'UTF-8', '-classpath', cls.dependencies, '-d', cls.directory,
                     recorder, *[lab.ROOT / 'app/src' / n for n in names],
                     *list((lab.ROOT / 'tests/stubs').rglob('*.java')), lab.ROOT / 'tests/RecordingTimingCheck.java'])

    def run_scenario(self, scenario):
        with tempfile.TemporaryDirectory(prefix='recording-timing-files-') as files:
            result = lab.command([lab.tool(self.settings, 'java'), '-Dfile.encoding=UTF-8', '-cp', str(self.directory) + os.pathsep + self.dependencies,
                                  'dev.xr.rayneo.probe.RecordingTimingCheck', scenario, files])
            output = result.stdout.decode('utf-8')
            self.assertIn('passed', output)
            receipts = list(Path(files).glob('recordings/*/receipt.json'))
            self.assertEqual(len(receipts), 1)
            receipt = json.loads(receipts[0].read_text(encoding='utf-8'))
            saved = scenario in ('success', 'gap-filled')
            self.assertEqual(receipt['phase'], 'saved' if saved else 'failed')
            timing = receipt['timing']
            self.assertEqual(timing['duration_ms']['stop_to_completed_report'], 100)
            self.assertEqual('saved_state' in timing['offset_ms'], saved)
            self.assertIn('raw_sealed', timing['offset_ms'])
            self.assertNotIn('decode', timing['duration_ms'])
            self.assertEqual('package' in timing['duration_ms'], saved)
            if saved:
                self.assertEqual(receipt['audio_file'], 'recording.ogg')
                self.assertEqual(receipt['silence_filled_packets'], 1 if scenario == 'gap-filled' else 0)

    def test_actual_stage_timing_preserves_saved_gate(self):
        self.run_scenario('success')

    def test_package_failure_retains_raw_completion_without_saved(self):
        self.run_scenario('package-failure')

    def test_missing_packet_is_filled_with_silence_not_failed(self):
        # 09-23: 720 missing bytes failed a whole 60-minute recording; the official app fills silence.
        self.run_scenario('gap-filled')
