import tempfile
import unittest

import lab
from test_interactions import method


class DashboardQueryChecks(unittest.TestCase):
    """A2UI baseline probe: does this firmware answer with widgets_v2 at all.

    The probe exists because the third-party evidence for that card surface was recovered from
    Strix OS 1.0.3.15 while our device runs 1.0.4.12. It reads and never installs.
    """

    def test_state_machine(self):
        settings = lab.settings()
        with tempfile.TemporaryDirectory() as tmp:
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8', '-d', tmp,
                         lab.ROOT / 'app/src/LabDashboardQuery.java',
                         lab.ROOT / 'tests/LabDashboardQueryCheck.java'])
            self.assertIn(b'dashboard query checks passed',
                          lab.command([lab.tool(settings, 'java'), '-cp', tmp,
                                       'dev.xr.rayneo.probe.LabDashboardQueryCheck']).stdout)

    def test_send_gate_compares_the_whole_body_not_just_the_cmd_name(self):
        """Sending our own invented shape would make a non-answer uninterpretable: we could not
        tell "firmware lacks the feature" from "firmware rejected our payload". Two independent
        third-party implementations send this exact body, so the gate pins all of it.

        An earlier version of this test only asserted that the string 'Unowned dashboard query'
        existed, which stayed green even if the gate were weakened to compare the cmd name alone.
        Independent review 2026-09-22 caught that; it now reads the gate expression itself.
        """
        source = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        gate = method(source, 'private void sendBusiness(')
        head, _, _ = gate.partition('Unowned dashboard query')
        branch = head[head.index('type==18&&dashboardQuery!=null'):]
        # Whole-body equality, not a cmd-name check.
        self.assertIn('json.toString().equals', branch)
        self.assertIn('.put("version",1).put("value",0)', branch)
        self.assertNotIn('optString("cmd")', branch)

    def test_timeout_is_actually_ticked_in_the_session_loop(self):
        """A state machine that is never ticked never times out. The most likely outcome of this
        probe is "firmware does not answer" -- if that is not converted into a timeout, the command
        stays pending and blocks every later command in the session. Independent review
        2026-09-22 found this missing."""
        source = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        self.assertIn('dashboardQuery.tick(', source)
        # Ticked next to its sibling, inside the same session loop.
        idx = source.index('dashboardQuery.tick(')
        window = source[max(0, idx - 400):idx]
        self.assertIn('settingsQuery.tick(', window)

    def test_serial_claim_is_enforced_not_only_claimed(self):
        """The branch comment says "serial read-only query". That must be backed by mutual
        exclusion with the other read paths, in both directions."""
        source = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        dash = source[source.index('activeCommandKind.equals("lab-dashboard-query")'):][:1200]
        self.assertIn('settingsQuery!=null&&!settingsQuery.done', dash)
        self.assertIn('brightnessJournal().getBoolean("pending",false)', dash)
        # ...and the settings read must exclude an in-flight dashboard read too.
        sett = source[source.index('activeCommandKind.equals("lab-settings-query")'):][:1200]
        self.assertIn('dashboardQuery!=null&&!dashboardQuery.done', sett)

    def test_probe_is_read_only(self):
        """The baseline read must not be able to install or remove a card.

        Until 2026-09-23 this asserted the whole activity never mentions widget_install. Card
        writes now exist as their own commands (lab-widget-install / -uninstall), so the check
        is narrowed to what it protects: the dashboard branch and its pinned body."""
        source = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        start = source.index('activeCommandKind.equals("lab-dashboard-query")')
        branch = source[start:source.index('} else if(', start + 1)]
        self.assertNotIn('widget', branch.replace('widgetCommand', ''))
        self.assertIn('LabDashboardQuery.CMD', branch)

    def test_weather_gate_still_intact(self):
        """Weather rides the same LAUNCHER type 18; its owner check must be untouched."""
        source = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        self.assertIn('Unowned weather command', source)


if __name__ == '__main__':
    unittest.main()
