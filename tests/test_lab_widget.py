import json
import tempfile
import unittest

import lab
from test_interactions import method


def source():
    return (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')


class WidgetCommandChecks(unittest.TestCase):
    """A2UI card install/uninstall (2026-09-23).

    The glasses on 1.0.4.12 answered the dashboard baseline read, so the card surface exists. These
    commands try the write side with one pinned card id of our own. The shape is the third-party
    project's (recovered from 1.0.3.15), unverified on our firmware -- which is what the device run
    is for. The checks here pin that we cannot write anything but our own card, and cannot report
    success without the glasses saying code 0.
    """

    def test_state_machine(self):
        settings = lab.settings()
        with tempfile.TemporaryDirectory() as tmp:
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8', '-d', tmp,
                         lab.ROOT / 'app/src/LabWidgetCommand.java',
                         lab.ROOT / 'tests/LabWidgetCommandCheck.java'])
            self.assertIn(b'widget command checks passed',
                          lab.command([lab.tool(settings, 'java'), '-cp', tmp,
                                       'dev.xr.rayneo.probe.LabWidgetCommandCheck']).stdout)

    def test_send_gate_compares_the_whole_pinned_body(self):
        gate = method(source(), 'private void sendBusiness(')
        head, _, _ = gate.partition('Unowned widget command')
        branch = head[head.index('type==18&&widgetCommand!=null'):]
        self.assertIn('json.toString().equals(widgetBody(widgetCommand.install).toString())', branch)
        self.assertNotIn('optString("cmd")', branch)

    def test_body_only_ever_names_our_card(self):
        body = method(source(), 'static JSONObject widgetBody(')
        # Every id/surface/widget reference goes through the one pinned constant.
        self.assertEqual(body.count('LabWidgetCommand.CARD_ID'), 1)
        self.assertIn('String card = LabWidgetCommand.CARD_ID;', body)
        for key in ('.put("id", card)', '.put("surfaceId", card)', '.put("widgetId", card)'):
            self.assertIn(key, body)

    def test_install_checks_the_baseline_before_sending(self):
        src = source()
        start = src.index('activeCommandKind.equals("lab-widget-install")')
        branch = src[start:src.index('} else if(activeCommandKind.equals("lab-todo-sync")', start)]
        self.assertIn('LabWidgetCommand.refusal(install,age,inBaseline,bodyBytes)', branch)
        # The size is measured on the very body that is sent, not on a copy.
        self.assertIn('int bodyBytes=widgetBody(install).toString().getBytes(', branch)
        self.assertLess(branch.index('int bodyBytes='), branch.index('sendBusiness('))
        self.assertLess(branch.index('refused!=null'), branch.index('sendBusiness('))
        # Serial with the baseline read and the settings read.
        self.assertIn('dashboardQuery!=null&&!dashboardQuery.done', branch)
        self.assertIn('settingsQuery!=null&&!settingsQuery.done', branch)
        self.assertIn('widgetCommand!=null&&!widgetCommand.done', branch)

    def test_baseline_ids_are_recorded_where_the_baseline_is_accepted(self):
        src = source()
        idx = src.index('lastBaselineAt = SystemClock.elapsedRealtime();')
        self.assertIn('dashboardQuery.reply(', src[max(0, idx - 1200):idx])

    def test_settings_reads_exclude_an_in_flight_card_write(self):
        """Serial in both directions (independent review 2026-09-23, M4): the manual settings
        read and the automatic settings sync share the LAUNCHER channel with card writes."""
        src = source()
        sett = src[src.index('activeCommandKind.equals("lab-settings-query")'):][:1400]
        self.assertIn('widgetCommand!=null&&!widgetCommand.done', sett)
        auto = method(src, 'private void autoSyncSettings(')
        self.assertIn('widgetCommand != null && !widgetCommand.done', auto)

    def test_unmatched_frames_are_kept_as_evidence(self):
        """If the ack shape changed on 1.0.4.12, "no_ack" alone would not tell a refusal from a
        changed reply (review M3); every LAUNCHER frame during the write is kept, capped."""
        src = source()
        self.assertIn('widget_candidate_frames', src)
        idx = src.index('result.put("widget_candidate_frames"')
        self.assertIn('if (widgetCommand != null && !widgetCommand.done) {', src[max(0, idx - 800):idx])

    def test_timeout_is_ticked_in_the_session_loop(self):
        src = source()
        idx = src.index('widgetCommand.tick(')
        self.assertIn('dashboardQuery.tick(', src[max(0, idx - 400):idx])

    def test_ack_requires_a_numeric_code(self):
        src = source()
        start = src.index('if (widgetCommand != null && !widgetCommand.done && wire.type == 19) {')
        block = src[start:start + 900]
        self.assertIn('ackRaw instanceof Number', block)
        self.assertIn('widgetCommand.reply(business, wire.type, ackCode', block)

    def test_cli_exposes_both_commands_as_lab_only(self):
        text = (lab.ROOT / 'session.py').read_text(encoding='utf-8')
        # kind whitelist, Lab-only list, CLI choices, mapping key, mapping value, timeout bucket.
        # Exact, so dropping any one registration (e.g. the Lab-only guard) turns this red.
        for kind in ('lab-widget-install', 'lab-widget-uninstall'):
            self.assertEqual(text.count("'%s'" % kind), 6, kind)

    def test_install_body_fits_the_reported_frame_budget(self):
        """The third-party author reports about 509 bytes as the proven single-frame size and
        marks larger messages unverified. Rebuild the body the way org.json writes it ('/' is
        escaped) and keep a margin under that."""
        card = 'aix_card_01'
        catalog = 'https://rayneo.com/a2ui/catalogs/glasses-base/v1/catalog.json'
        extras = json.dumps({'name': 'AIX', 'uiContent': {
            'createSurface': {'catalogId': catalog, 'surfaceId': card},
            'updateComponents': {'components': [{'component': 'Text', 'id': 'root',
                                                 'text': 'AIX 测试', 'variant': 'body'}],
                                 'surfaceId': card}}, 'widgetId': card},
            ensure_ascii=False, separators=(',', ':')).replace('/', '\\/')
        body = json.dumps({'cmd': 'widget_install', 'payload': {'data': {
            'extras': extras, 'id': card, 'name': 'AIX', 'type': 'a2ui'}}},
            ensure_ascii=False, separators=(',', ':')).replace('/', '\\/')
        # Same gate as the third party: packet (body + 7 envelope bytes) + 10 <= 509.
        size = len(body.encode('utf-8'))
        self.assertLessEqual(size + 7 + 10, 509, size)
        # The Java constants this mirrors must still be the ones in the source.
        java = (lab.ROOT / 'app/src/LabWidgetCommand.java').read_text(encoding='utf-8')
        for literal in ('"aix_card_01"', '"AIX"', '"AIX 测试"', 'MAX_BODY_BYTES = 492'):
            self.assertIn(literal, java)


if __name__ == '__main__':
    unittest.main()
