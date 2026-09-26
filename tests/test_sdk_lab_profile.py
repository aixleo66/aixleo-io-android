import tempfile
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET
import lab


class SdkLabProfileTest(unittest.TestCase):
    def test_daily_manifest_is_unchanged(self):
        source = lab.ROOT / 'app/AndroidManifest.xml'
        with tempfile.TemporaryDirectory() as temp:
            target = Path(temp) / 'AndroidManifest.xml'
            self.assertEqual(lab.PACKAGE, lab.write_build_manifest(source, target, 'daily'))
            self.assertEqual(source.read_bytes(), target.read_bytes())

    def test_lab_can_coexist_and_has_only_safe_exported_activity(self):
        ns = '{http://schemas.android.com/apk/res/android}'
        with tempfile.TemporaryDirectory() as temp:
            target = Path(temp) / 'AndroidManifest.xml'
            lab.write_build_manifest(lab.ROOT / 'app/AndroidManifest.xml', target, 'sdk-lab')
            root = ET.parse(target).getroot()
            self.assertNotEqual(lab.PACKAGE, root.get('package'))
            self.assertEqual(lab.LAB_PACKAGE, root.get('package'))
            app = root.find('application')
            self.assertEqual('AIX IO SDK Lab', app.get(ns + 'label'))
            activities = app.findall('activity')
            exported = [a for a in activities if a.get(ns + 'exported') == 'true']
            self.assertEqual([lab.PACKAGE + '.SdkLabActivity'], [a.get(ns + 'name') for a in exported])
            self.assertEqual(1, len(app.findall('activity/intent-filter')))
            self.assertTrue(all(a.get(ns + 'name').startswith(lab.PACKAGE + '.') for a in activities))
            self.assertIsNone(app.get(ns + 'sharedUserId'))
            self.assertIsNone(root.get(ns + 'sharedUserId'))


if __name__ == '__main__':
    unittest.main()
