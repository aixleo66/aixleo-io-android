import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

import lab


class VendorInputChecks(unittest.TestCase):
    def test_unknown_and_missing_input_are_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / 'unknown.jar'
            self.assertIsNone(lab.vendor_input_kind(p))
            p.write_bytes(b'not a trusted vendor input')
            self.assertIsNone(lab.vendor_input_kind(p))

    def test_pinned_payload_requires_exact_entries_and_rejects_tamper(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / 'payload.jar'
            with zipfile.ZipFile(p, 'w') as z:
                for name in lab.PAYLOAD_ENTRIES:
                    z.writestr(name, b'synthetic fixture')
            with patch.object(lab, 'PAYLOAD_HASH', lab.digest(p)):
                self.assertEqual(lab.vendor_input_kind(p), 'pinned_vendor_payload')
                with zipfile.ZipFile(p, 'a') as z:
                    z.writestr('unexpected.dex', b'untrusted')
                self.assertIsNone(lab.vendor_input_kind(p))
            with patch.object(lab, 'PAYLOAD_HASH', lab.digest(p)):
                self.assertIsNone(lab.vendor_input_kind(p))

    def test_candidate_default_keeps_explicit_input_override(self):
        with tempfile.TemporaryDirectory() as d, patch.object(lab, 'ROOT', Path(d)):
            payload = Path(d) / 'vendor/rayneo-venus-1.0.2-68/vendor-payload.jar'
            payload.parent.mkdir(parents=True)
            payload.write_bytes(b'placeholder; selection is separate from validation')
            self.assertEqual(lab.settings()['sample'], payload.resolve())
            lab.write_json(Path(d) / 'config.local.json', {'sample': 'own.apk'})
            self.assertEqual(lab.settings()['sample'], (Path(d) / 'own.apk').resolve())


if __name__ == '__main__':
    unittest.main()
