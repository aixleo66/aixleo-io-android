from pathlib import Path
import re
import tempfile
import unittest
import lab


class SourceLevelChecks(unittest.TestCase):
    """The production build compiles at -source 8; every offline check must do the same.

    On 2026-09-20 `VoicePhrase.java` shipped a `"\\s"` literal written with one backslash. Under
    -source 8 javac rejects it outright ("text blocks not supported"), so the build would have
    failed; the offline suite compiled the same file without -source and passed all 162 checks.
    This is the third time in one day that a harness diverging from production let a defect
    through, so the source level is now pinned by a test rather than by habit.
    """

    def test_production_still_builds_at_source_eight(self):
        build = (lab.ROOT / 'lab.py').read_text(encoding='utf-8')
        self.assertIn("'-source', '8', '-target', '8'", build)

    def test_every_production_class_compiles_at_that_level(self):
        settings = lab.settings()
        sources = sorted(lab.production_sources())  # subpackages included (review 09-23)
        self.assertGreater(len(sources), 30, 'no production sources found')
        stubs = list((lab.ROOT / 'tests/stubs/org/json').glob('*.java'))
        stubs += list((lab.ROOT / 'tests/stubs/android/os').glob('*.java'))
        with tempfile.TemporaryDirectory() as directory:
            # Compile the leaf classes that carry their own regexes and constants. The Activity
            # itself needs the Android framework, which the stubs do not provide, so it is covered
            # by the real build; these are the files most likely to grow a newer-than-8 literal.
            leaves = [p for p in sources if p.name in (
                'VoicePhrase.java', 'VoiceExitPhrase.java', 'VoiceRecordingAction.java',
                'VoiceTodoAction.java', 'DisplayedAnswerPolicy.java', 'AsrPartialGate.java',
                'SessionCommandGate.java', 'VoiceWakePolicy.java', 'AnswerPolicy.java',
                'NotificationPolicy.java', 'ConnectionReadiness.java')]
            self.assertGreater(len(leaves), 8, 'leaf list drifted from app/src')
            lab.command([lab.tool(settings, 'javac'), '-J-Duser.language=en', '-encoding', 'UTF-8',
                         '-source', '8', '-target', '8', '-nowarn', '-d', directory,
                         *leaves, *stubs])

    def test_no_java_nine_plus_escape_slips_into_a_regex(self):
        # "\\s" written with a single backslash is a text-block escape, not the whitespace class.
        # It is silently wrong where it compiles and fatal where it does not.
        for path in lab.production_sources():
            text = path.read_text(encoding='utf-8')
            for number, line in enumerate(text.split('\n'), 1):
                if line.lstrip().startswith(('//', '*')):
                    continue
                for bad in re.finditer(r'(?<!\\)\\[sdwSDWbB]', line):
                    self.fail('%s:%d uses a single-backslash regex escape %r: %s'
                              % (path.name, number, bad.group(0), line.strip()))


class TransportHandbackChecks(unittest.TestCase):
    """Option 甲 (2026-09-21): the app hands the SPP channel back in exactly one place.

    Releasing it after a recording put the glasses into "请将眼镜连接至雷鸟AI", where nothing the
    user said afterwards was answered. The same shape had already been patched around twice --
    once with a generation guard, once by chasing the audio link -- without anyone asking whether
    the release belonged there at all. The official app never releases: it held the channel for
    3.5 hours at 1.96 %/h, below our own 3.44 %/h without it.

    These are source-text assertions on purpose. A behavioural fixture cannot see a fourth copy of
    the vendor sequence being pasted somewhere new, and pasting it is exactly how this defect was
    introduced and preserved. The release belongs to session cleanup; recording completion only drops its transport claim.
    """

    def sdk_source(self):
        return (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')

    def test_the_vendor_handback_is_invoked_from_exactly_one_method(self):
        # The SPP channel is S3.B.c; S3.B.b is BLE and is released at session end as it always was.
        sites = [(number, line) for number, line in enumerate(self.sdk_source().splitlines(), 1)
                 if 'getField("c")' in line and '.invoke(' in line]
        self.assertEqual(len(sites), 1,
                         'the SPP handback sequence exists in %d places, not 1: %s'
                         % (len(sites), [n for n, _ in sites]))
        self.assertIn('sppRequested=false', sites[0][1])

    def test_only_session_end_and_the_explicit_command_ask_for_the_handback(self):
        callers = re.findall(r'submitTransportRelease\("([a-z_]+)"\)', self.sdk_source())
        self.assertEqual(sorted(callers), ['explicit_spp_off', 'session_cleanup_spp'],
                         'a new caller hands SPP back: %s' % sorted(callers))

    def test_a_finished_recording_only_drops_its_claim(self):
        source = self.sdk_source()
        # Ending a recording, a failed preparation and a cancelled preparation all go through the
        # method that cannot release, and nothing schedules a release behind them.
        claims = re.findall(r'endRecordingTransportUse\("([a-z_]+)"\)', source)
        self.assertEqual(sorted(claims),
                         ['preparation_cancelled', 'preparation_failed', 'recording_end'],
                         'a recording path stopped dropping its claim, or gained a new one: %s'
                         % sorted(claims))
        for gone in ('delayed_release', 'releaseRecordingTransport'):
            self.assertNotIn(gone, source, '%s came back' % gone)

    def test_the_recording_docs_do_not_state_the_old_behaviour_as_current(self):
        # Historical observations do not change the current ownership rule; what must not
        # come back is a current-state doc telling the next person to release after saving.
        for name in ('docs/cn/FEATURES-0.2.1.md', 'docs/cn/TROUBLESHOOTING.md'):
            text = (lab.ROOT / name).read_text(encoding='utf-8')
            self.assertNotIn('保存后立即释放 SPP', text, '%s repeats the retired rule' % name)

    def test_every_ui_command_kind_exists_in_the_dispatcher(self):
        """A kind the dispatcher does not know fails the whole session, not just the command.

        `sessionTick` ends its dispatch chain with `throw new IllegalArgumentException("Unknown
        session command")`, and that throw is caught by the tick's own `catch(Throwable e){
        complete("failed", ...) }`. So one wrong string in a button handler tears down the session
        the moment it is pressed.

        Shipped on 2026-09-22: the settings page called sendSessionCommand("query"), reasoning from
        the CLI action name. `query` is a *CLI action* that maps to kind `status`; the dispatcher
        has no such branch and session.py's kind whitelist does not list it either. It went
        unnoticed because the gate happened to reject it earlier for an unrelated reason during
        testing. Independent review found it by reading the dispatch chain.
        """
        ui = (lab.ROOT / 'app/src/CloudActivity.java').read_text(encoding='utf-8')
        activity = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        session = (lab.ROOT / 'session.py').read_text(encoding='utf-8')

        # Scan every entry point that carries a kind literal, not just sendSessionCommand: a
        # wrapper passes the kind as a *variable*, so the literal lives at the wrapper's call site.
        # The first version of this test only scanned sendSessionCommand and therefore did not
        # catch the very defect it was written for -- readGlasses("query") sailed straight through.
        # Verified by re-injecting the defect and watching this fail. Add new wrappers here.
        used = set(re.findall(r'sendSessionCommand\(\s*"([a-z0-9-]+)"', ui))
        used |= set(re.findall(r'readGlasses\(\s*"([a-z0-9-]+)"', ui))
        self.assertTrue(used, 'no literal command kinds found; the scan pattern went stale')
        self.assertIn('status', used, 'readGlasses scan went stale: the settings page reads status')

        known = set(re.findall(r'activeCommandKind\.equals\("([a-z0-9-]+)"\)', activity))
        known |= set(re.findall(r'kind\.equals\("([a-z0-9-]+)"\)', activity))
        # The chain also dispatches whole groups at once, e.g.
        # Arrays.asList("voice","audio",...).contains(activeCommandKind). Scanning only for
        # .equals() missed those and this test reported voice as unknown on its first run.
        for line in activity.splitlines():
            if 'contains(activeCommandKind)' in line:
                known |= set(re.findall(r'"([a-z0-9-]+)"', line))
        # startJob/notify style kinds are dispatched by their own branches keyed on other fields.
        known |= {'notify', 'stop'}
        unknown = sorted(used - known)
        self.assertEqual([], unknown,
                         'UI sends kinds the dispatcher has no branch for: %s' % unknown)

        # session.py guards the same kinds for CLI callers; keeping the two in step means a kind
        # can be exercised from either side without one of them silently rejecting it.
        whitelist = re.search(r'if kind not in \((.*?)\):', session, re.S)
        self.assertIsNotNone(whitelist, 'session.py kind whitelist moved; update this test')
        listed = set(re.findall(r"'([a-z0-9-]+)'", whitelist.group(1)))
        missing = sorted(k for k in used if k not in listed)
        self.assertEqual([], missing,
                         'UI sends kinds session.py would reject: %s' % missing)
