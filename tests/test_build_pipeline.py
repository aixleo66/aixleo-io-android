import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
import lab

NS = '{http://schemas.android.com/apk/res/android}'


class BuildPipelineChecks(unittest.TestCase):
    """Stage 3.0: XML layouts, R.* and subpackages must reach the production build.

    Before this the build compiled only flat app/src/*.java and aapt2 link never wrote R.java,
    so a layout could be packaged but no code could refer to it.
    """

    def test_sources_are_collected_recursively(self):
        sources = lab.production_sources()
        relative = {p.relative_to(lab.ROOT / 'app/src').as_posix() for p in sources}
        self.assertIn('CloudActivity.java', relative)
        self.assertIn('ui/UiProbeActivity.java', relative)
        # Not flat any more: the subpackage file is found (a flat glob would miss it).
        self.assertGreater(len(sources), len(list((lab.ROOT / 'app/src').glob('*.java'))))

    def test_build_links_resources_before_javac_and_compiles_r(self):
        body = (lab.ROOT / 'lab.py').read_text(encoding='utf-8')
        build = body[body.index('def build_in_workspace('):body.index('def latest_output(')]
        self.assertIn('sources = production_sources()', build)
        self.assertLess(build.index('link_resources('), build.index("tool(s, 'javac')"))
        self.assertIn('*sources, generated, r_java]', build)
        link = body[body.index('def link_resources('):body.index('def build_in_workspace(')]
        self.assertIn("'--java', r_dir, '--custom-package', PACKAGE", link)
        self.assertIn("'r_java_generated'", build)

    def test_link_generates_r_and_subpackage_compiles_against_it(self):
        settings = lab.settings()
        with lab.build_workspace() as tmp:
            output = Path(tmp)
            for profile in ('sdk-lab', 'daily'):
                work = output / profile
                work.mkdir()
                lab.write_build_manifest(lab.ROOT / 'app/AndroidManifest.xml', work / 'AndroidManifest.xml', profile)
                _, r_java = lab.link_resources(settings, work, work / 'AndroidManifest.xml', work / 'res.apk')
                text = r_java.read_text(encoding='utf-8')
                # Same R package for both profiles, although sdk-lab renames the application id.
                self.assertIn('package dev.xr.rayneo.probe;', text)
                for name in ('ui_probe', 'ui_probe_title', 'ui_probe_background', 'ui_probe_text', 'CompanionDark'):
                    self.assertRegex(text, r'\b%s\s*=' % name)
            work = output / 'sdk-lab'
            classes = work / 'classes'
            lab.command([lab.tool(settings, 'javac'), '-J-Duser.language=en', '-encoding', 'UTF-8',
                         '-source', '8', '-target', '8', '-nowarn', '-classpath', settings['android_jar'],
                         '-d', classes, lab.ROOT / 'app/src/ui/UiProbeActivity.java',
                         work / 'generated/r/dev/xr/rayneo/probe/R.java'])
            self.assertTrue((classes / 'dev/xr/rayneo/probe/ui/UiProbeActivity.class').is_file())
            self.assertTrue((classes / 'dev/xr/rayneo/probe/R$layout.class').is_file())

    def test_probe_activity_uses_the_layout_and_stays_private(self):
        source = (lab.ROOT / 'app/src/ui/UiProbeActivity.java').read_text(encoding='utf-8')
        self.assertIn('package dev.xr.rayneo.probe.ui;', source)
        self.assertIn('setContentView(R.layout.ui_probe)', source)
        layout = (lab.ROOT / 'app/res/layout/ui_probe.xml').read_text(encoding='utf-8')
        colors = (lab.ROOT / 'app/res/values/ui_probe_colors.xml').read_text(encoding='utf-8')
        for name in re.findall(r'@color/(\w+)', layout):
            self.assertIn('name="%s"' % name, colors)
        self.assertTrue(re.findall(r'@color/(\w+)', layout))
        app = ET.parse(lab.ROOT / 'app/AndroidManifest.xml').getroot().find('application')
        probe = [c for c in app if c.get(NS + 'name') == '.ui.UiProbeActivity']
        self.assertEqual(len(probe), 1)
        self.assertEqual(probe[0].get(NS + 'exported'), 'false')
        self.assertIsNone(probe[0].find('intent-filter'))
        # Not wired into any existing entry point.
        for path in lab.production_sources():
            if path.name != 'UiProbeActivity.java':
                self.assertNotIn('UiProbeActivity', path.read_text(encoding='utf-8'), path.name)


if __name__ == '__main__':
    unittest.main()
