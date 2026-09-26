import tempfile
import unittest
from unittest.mock import patch
import lab
import session

class FirmwareChecks(unittest.TestCase):
    def test_timeout_wrong_type_bad_reply_late_response_and_ack_order(self):
        s=lab.settings()
        with tempfile.TemporaryDirectory() as tmp:
            lab.command([lab.tool(s,'javac'),'-encoding','UTF-8','-d',tmp,lab.ROOT/'app/src/LabFirmwareQuery.java',lab.ROOT/'tests/LabFirmwareQueryCheck.java'])
            self.assertIn(b'checks passed',lab.command([lab.tool(s,'java'),'-cp',tmp,'dev.xr.rayneo.probe.LabFirmwareQueryCheck']).stdout)
    def test_daily_rejected_before_device(self):
        with patch.object(lab,'adb_result') as adb:
            with self.assertRaises(ValueError):session.send({},'unused','lab-firmware-query',package=lab.PACKAGE)
            adb.assert_not_called()
