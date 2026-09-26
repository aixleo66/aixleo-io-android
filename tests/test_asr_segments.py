import os
from pathlib import Path
import tempfile
import unittest
import lab


class SegmentChecks(unittest.TestCase):
    def test_real_item_accumulator_and_echo_whitelist(self):
        s = lab.settings()
        with tempfile.TemporaryDirectory() as tmp:
            lab.command([lab.tool(s, 'javac'), '-J-Duser.language=en', '-encoding', 'UTF-8', '-d', tmp,
                         lab.ROOT / 'app/src/AsrSegments.java', lab.ROOT / 'tests/AsrSegmentsCheck.java',
                         *list((lab.ROOT / 'tests/stubs/org/json').glob('*.java'))])
            lab.command([lab.tool(s, 'java'), '-cp', tmp, 'dev.xr.rayneo.probe.AsrSegmentsCheck'])

    def test_real_stream_loop_drains_empty_and_nonempty_eos_before_finish(self):
        s = lab.settings()
        with tempfile.TemporaryDirectory() as tmp:
            lab.command([lab.tool(s, 'javac'), '-J-Duser.language=en', '-encoding', 'UTF-8', '-d', tmp,
                         lab.ROOT / 'app/src/StreamingAsr.java', lab.ROOT / 'app/src/AsrSegments.java',
                         lab.ROOT / 'app/src/PcmDownsample.java',
                         *list((lab.ROOT / 'tests/stubs/org/json').glob('*.java')),
                         *list((lab.ROOT / 'tests/streaming_stubs').rglob('*.java'))])
            for variant in ('empty-eos', 'pcm-eos', 'grace', 'grace-no-ack', 'late-partial'):
                lab.command([lab.tool(s, 'java'), '-cp', tmp, 'dev.xr.rayneo.probe.StreamLoopCheck', variant])
