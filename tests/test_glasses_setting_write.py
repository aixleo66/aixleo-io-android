from pathlib import Path
import tempfile
import unittest
import lab


class GlassesSettingWriteChecks(unittest.TestCase):
    """The first code path in this project that can change a user's glasses.

    Until now every setting write went through a lab trial, which writes a value and restores the
    original seconds later -- so a wrong value was self-correcting. A product write stays, which
    makes the accepted ranges the only thing between a typo and a device the user has to fix by
    hand. They are pinned here rather than in the UI.
    """

    def test_ranges_and_packet_shapes(self):
        settings = lab.settings()
        with tempfile.TemporaryDirectory() as directory:
            lab.command([lab.tool(settings, 'javac'), '-J-Duser.language=en', '-encoding', 'UTF-8',
                         '-source', '8', '-target', '8', '-nowarn', '-d', directory,
                         lab.ROOT / 'app/src/GlassesSettingWrite.java',
                         lab.ROOT / 'tests/GlassesSettingWriteCheck.java',
                         *list((lab.ROOT / 'tests/stubs/org/json').glob('*.java'))])
            result = lab.command([lab.tool(settings, 'java'), '-cp', directory,
                                  'dev.xr.rayneo.probe.GlassesSettingWriteCheck'])
            self.assertIn(b'glasses setting write checks passed', result.stdout)

    def test_the_write_path_is_not_a_trial(self):
        """A product write must not inherit the trials' restore-after-N-seconds behaviour.

        Scans code only: the class comment explains at length why it does *not* restore, and a
        naive search over the whole file matches that explanation instead of any real scheduling.
        """
        source = (lab.ROOT / 'app/src/GlassesSettingWrite.java').read_text(encoding='utf-8')
        code = chr(10).join(line for line in source.splitlines()
                            if not line.strip().startswith(('*', '//', '/*')))
        for trap in ('postDelayed', 'Handler', 'restore(', 'Trial', 'SystemClock'):
            self.assertNotIn(trap, code,
                             'the product write path must not schedule anything: %s' % trap)
        # It must also stay a pure builder: no sending, no storage, nothing to undo later.
        for trap in ('sendBusiness', 'getSharedPreferences', 'FileOutputStream'):
            self.assertNotIn(trap, code, 'the write builder must have no side effects: %s' % trap)


class SettingWriteGateChecks(unittest.TestCase):
    """Two gates guard a setting write and they have to agree.

    The device gate (SessionCommandGate) refuses most commands while voice standby is on; the UI
    gate (CommandWait) refuses anything while it is already waiting on a command. Standby is a
    long-lived command, so on 2026-09-22 the UI gate silently swallowed every settings write even
    after the device gate had been opened -- the button simply did nothing, with no message,
    because the caller just returns. Both must exempt apply-setting or neither should.
    """

    def test_both_gates_exempt_apply_setting(self):
        """The device gate is checked by text (its rule is a literal list); the UI gate is compiled
        and called, because a string search there also matches the javadoc -- which is how the
        first version of this test would have passed even with the exemption turned into dead code.
        """
        device = (lab.ROOT / 'app/src/SessionCommandGate.java').read_text(encoding='utf-8')
        standby_rule = device.split('if (standby &&')[1].split('return "standby_busy"')[0]
        self.assertIn('apply-setting', standby_rule,
                      'device gate blocks settings writes while standby is on')
        settings = lab.settings()
        with tempfile.TemporaryDirectory() as directory:
            lab.command([lab.tool(settings, 'javac'), '-J-Duser.language=en', '-encoding', 'UTF-8',
                         '-source', '8', '-target', '8', '-nowarn', '-d', directory,
                         lab.ROOT / 'app/src/CommandWait.java',
                         lab.ROOT / 'tests/CommandWaitCheck.java'])
            result = lab.command([lab.tool(settings, 'java'), '-cp', directory,
                                  'dev.xr.rayneo.probe.CommandWaitCheck'])
            self.assertIn(b'command wait checks passed', result.stdout)

    def test_the_write_is_refused_when_the_glasses_are_not_ready(self):
        """Connection readiness is a real constraint and must not be exempted along with the rest."""
        activity = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        apply_method = activity.split('private void applyGlassesSetting(')[1][:900]
        self.assertIn('connectionReady()', apply_method,
                      'a setting write must still require a ready connection')
        self.assertIn('Refusal', apply_method, 'refusals must reach the user, not be swallowed')
