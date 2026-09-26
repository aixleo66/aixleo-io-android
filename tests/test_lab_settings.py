import unittest,tempfile
from unittest.mock import patch
import lab,session
from pathlib import Path
from test_interactions import method
class SettingsChecks(unittest.TestCase):
 def test_production_snapshot_filter_and_three_serial_reads(self):
  s=lab.settings();source=(lab.ROOT/'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
  methods='\n'.join(method(source,signature) for signature in (
   'private static String statusShape(', 'private void inspectSettingsSnapshot(',
   'private void beginSettingsQuery(', 'private void captureSettingsReply(', 'private void publishSettingsQuery('))
  with tempfile.TemporaryDirectory() as tmp:
   java=Path(tmp)/'SettingsQueryIntegrationCheck.java'
   java.write_text((lab.ROOT/'tests/SettingsQueryIntegrationCheck.java.in').read_text(encoding='utf-8').replace('// PRODUCTION_METHODS',methods),encoding='utf-8')
   lab.command([lab.tool(s,'javac'),'-encoding','UTF-8','-d',tmp,java,lab.ROOT/'app/src/LabSettingsQuery.java',lab.ROOT/'app/src/ReportedSettingsPolicy.java',lab.ROOT/'tests/stubs/android/os/SystemClock.java',*list((lab.ROOT/'tests/stubs/org/json').glob('*.java'))])
   self.assertIn(b'three serial reads passed',lab.command([lab.tool(s,'java'),'-cp',tmp,'dev.xr.rayneo.probe.SettingsQueryIntegrationCheck']).stdout)
 def test_unknown_and_private_values_filtered(self):
  s=lab.settings()
  with tempfile.TemporaryDirectory() as tmp:
   lab.command([lab.tool(s,'javac'),'-encoding','UTF-8','-d',tmp,lab.ROOT/'app/src/ReportedSettingsPolicy.java',lab.ROOT/'tests/ReportedSettingsPolicyCheck.java'])
   self.assertIn(b'checks passed',lab.command([lab.tool(s,'java'),'-cp',tmp,'dev.xr.rayneo.probe.ReportedSettingsPolicyCheck']).stdout)
 def test_daily_rejected_before_device(self):
  with patch.object(lab,'adb_result') as adb:
   with self.assertRaises(ValueError):session.send({},'unused','lab-settings-query',package=lab.PACKAGE)
   adb.assert_not_called()
 def test_cli_route(self):
  with patch('sys.argv',['session.py','lab-settings-query','--serial','unused','--profile','sdk-lab']),patch.object(lab,'settings',return_value={}),patch.object(session,'send',return_value={'status':'completed'}) as send,patch('builtins.print'):
   self.assertEqual(session.main(),0);self.assertEqual(send.call_args.args[2],'lab-settings-query');self.assertEqual(send.call_args.kwargs['timeout'],20)
