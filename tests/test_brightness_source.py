import re
import tempfile
import unittest
from pathlib import Path

import lab
from test_interactions import method


def without_comments(text):
    """Assertions on source text must not be satisfied by a comment that merely mentions the name.

    On 2026-09-22 a check for 'apply-setting' passed because the string appeared in a javadoc
    paragraph, not in the dispatcher it was meant to pin.
    """
    return re.sub(r'/\*.*?\*/', '', re.sub(r'//[^\n]*', '', text), flags=re.S)


class BrightnessSourceChecks(unittest.TestCase):
    """The glasses adjust brightness themselves and push the new level; the snapshot does not."""

    def test_newer_source_wins_and_missing_stays_missing(self):
        settings = lab.settings()
        source = (lab.ROOT / 'app/src/CloudActivity.java').read_text(encoding='utf-8')
        # One placeholder per method rather than joining the bodies: a separator written as an
        # escape is how a shell-driven edit turned '\n' into a real newline here on 2026-09-22.
        harness = (lab.ROOT / 'tests/BrightnessSourceCheck.java.in').read_text(encoding='utf-8')
        harness = harness.replace('// PRODUCTION_METHODS_2',
                                  method(source, 'private static boolean pushWins('))
        harness = harness.replace('// PRODUCTION_METHODS',
                                  method(source, 'private static Integer newerBrightness('))
        with tempfile.TemporaryDirectory() as tmp:
            java = Path(tmp) / 'BrightnessSourceCheck.java'
            java.write_text(harness, encoding='utf-8')
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8', '-d', tmp, java,
                         *list((lab.ROOT / 'tests/stubs/org/json').glob('*.java'))])
            self.assertIn(b'brightness source checks passed',
                          lab.command([lab.tool(settings, 'java'), '-cp', tmp,
                                       'dev.xr.rayneo.probe.BrightnessSourceCheck']).stdout)

    def test_the_push_is_parsed_not_merely_logged(self):
        code = without_comments((lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8'))
        self.assertIn('command.equals("brightness_change")', code)
        self.assertIn('result.put("glasses_brightness"', code)
        # The ambient reading is what distinguishes a firmware adjustment from our own write.
        self.assertIn('light.put("lux"', code)
        # A push with no level is not a reading: storing one would stamp at_ms as "something just
        # happened" while dropping the level already known.
        self.assertIn('&& payload.opt("value") instanceof Number', code)
        self.assertNotIn('if (payload.opt("value") instanceof Number) light.put("brightness"', code)

    def test_the_slider_reads_the_same_source_and_invents_nothing(self):
        """Scanning the whole file for a literal is not enough: the first version of this test
        passed against a reintroduced default because the injected text carried one more
        character. The check is scoped to the method body and states what may not appear in it."""
        source = (lab.ROOT / 'app/src/CloudActivity.java').read_text(encoding='utf-8')
        body = without_comments(method(source, 'private void buildSettingControls('))
        # final is the real guard: reassigning a default here fails the build, which a
        # source-text assertion cannot enforce -- an injected `brightNow = valueOf(7)`
        # slipped past the text checks below during review of this very test.
        self.assertIn('final Integer brightNow = newerBrightness(state, status);', body)
        # Reading the snapshot directly here is what let a missing level default to 7.
        self.assertNotIn('opt("brightness")', body)
        self.assertNotIn(': 7', body)

    def test_an_unknown_level_hides_neither_the_slider_nor_the_uncertainty(self):
        """Brightness is a single-field write, so a starting position cannot overwrite a setting
        nobody touched -- the reason the multi-field groups go read-only does not apply. Routing
        it through missingGroup removed the control and pointed the user at 「刷新眼镜设置」,
        which is the type4 read and carries no brightness at all."""
        source = (lab.ROOT / 'app/src/CloudActivity.java').read_text(encoding='utf-8')
        body = without_comments(method(source, 'private void buildSettingControls('))
        self.assertNotIn('missingGroup("亮度")', body)
        self.assertIn('当前值未读到', body)
        # The other groups must keep using it -- for them the hint and the read-only are right.
        self.assertIn('missingGroup("自动息屏")', body)


if __name__ == '__main__':
    unittest.main()
