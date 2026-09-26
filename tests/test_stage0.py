import json
import re
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest.mock import patch

import lab
from test_interactions import method

STUBS = list((lab.ROOT / 'tests/stubs/org/json').glob('*.java'))


def src(name):
    return (lab.ROOT / name).read_text(encoding='utf-8')


def run_check(classes, main):
    s = lab.settings()
    with tempfile.TemporaryDirectory() as tmp:
        lab.command([lab.tool(s, 'javac'), '-J-Duser.language=en', '-encoding', 'UTF-8', '-d', tmp,
                     *[lab.ROOT / c for c in classes], *STUBS])
        return lab.command([lab.tool(s, 'java'), '-cp', tmp, 'dev.xr.rayneo.probe.' + main]).stdout


class Stage0Defaults(unittest.TestCase):
    """09-23 plan stage 0.1 / 0.4: the daily app is retired, so an unqualified build, session call
    or verify means the SDK Lab; the Lab version has exactly one definition."""

    def test_build_and_session_default_to_sdk_lab(self):
        self.assertIn("choices=['daily', 'sdk-lab'], default='sdk-lab'", src('lab.py'))
        self.assertIn("def build(s, profile='sdk-lab'):", src('lab.py'))
        self.assertIn("choices=('daily', 'sdk-lab'), default='sdk-lab'", src('session.py'))

    def test_latest_output_prefers_the_lab_pointer(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'out').mkdir()
            (root / 'out/latest-build.json').write_text(json.dumps({'directory': 'out/builds/daily'}), encoding='utf-8')
            with patch.object(lab, 'ROOT', root):
                self.assertTrue(str(lab.latest_output()).replace('\\', '/').endswith('out/builds/daily'),
                                'falls back to the daily pointer when no Lab build exists')
                (root / 'out/latest-sdk-lab-build.json').write_text(json.dumps({'directory': 'out/builds/lab'}), encoding='utf-8')
                self.assertTrue(str(lab.latest_output()).replace('\\', '/').endswith('out/builds/lab'))
                self.assertTrue(str(lab.latest_output('out/builds/x')).replace('\\', '/').endswith('out/builds/x'),
                                'an explicit --output still wins')

    def test_lab_version_has_one_source(self):
        version = json.loads(src('app/lab-version.json'))
        self.assertEqual(lab.lab_version(), {'versionCode': version['versionCode'], 'versionName': version['versionName']})
        text = src('lab.py')
        start = text.index('def write_build_manifest(')
        body = text[start:text.index('\ndef ', start + 1)]
        self.assertIn('lab_version()', body)
        self.assertNotIn("'1864'", body)
        self.assertNotIn("'0.18-sdk-lab.64'", body)

    def test_lab_manifest_carries_the_single_source_version(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / 'AndroidManifest.xml'
            lab.write_build_manifest(lab.ROOT / 'app/AndroidManifest.xml', out, 'sdk-lab')
            root = ET.parse(out).getroot()
            ns = '{http://schemas.android.com/apk/res/android}'
            version = lab.lab_version()
            self.assertEqual(root.get(ns + 'versionCode'), str(version['versionCode']))
            self.assertEqual(root.get(ns + 'versionName'), version['versionName'])
            self.assertEqual(root.get('package'), lab.LAB_PACKAGE)

    def test_version_file_is_in_the_build_source_hashes(self):
        text = src('lab.py')
        i = text.index("'source_sha256'")
        self.assertIn("ROOT / 'app/lab-version.json'", text[i:i + 400])

    def test_bad_version_file_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'app').mkdir()
            for bad in ({'versionCode': 0, 'versionName': 'x'}, {'versionCode': True, 'versionName': 'x'},
                        {'versionCode': 3, 'versionName': ' '}, {'versionName': 'x'}):
                (root / 'app/lab-version.json').write_text(json.dumps(bad), encoding='utf-8')
                with patch.object(lab, 'ROOT', root), self.assertRaises(ValueError):
                    lab.lab_version()


class Stage0ConfigDrift(unittest.TestCase):
    """09-23 plan stage 0.2: a recording cap frozen at the retired default 60 ran a 60-minute
    recording for a user who never changed it."""

    def test_rules(self):
        self.assertIn(b'config persistence checks passed',
                      run_check(['app/src/ConfigPersistence.java', 'tests/ConfigPersistenceCheck.java'],
                                'ConfigPersistenceCheck'))

    def test_save_stores_only_non_default_values(self):
        save = method(src('app/src/CloudConfig.java'), 'static void save(')
        self.assertIn('ConfigPersistence.sparse(value, defaults())', save)
        # Validation still sees the full object; only what is written is sparse.
        self.assertLess(save.index('CloudClient.validateConfig(value)'), save.index('ConfigPersistence.sparse('))
        self.assertIn('stored.toString()', save)
        self.assertNotIn('value.toString().getBytes', save)

    def test_load_drops_retired_defaults_before_filling_defaults(self):
        load = method(src('app/src/CloudConfig.java'), 'static JSONObject load(')
        self.assertLess(load.index('ConfigPersistence.dropRetiredDefaults(loaded)'), load.index('JSONObject defaults = defaults();'))

    def test_migration_runs_once_and_only_for_old_files(self):
        load = method(src('app/src/CloudConfig.java'), 'static JSONObject load(')
        save = method(src('app/src/CloudConfig.java'), 'static void save(')
        self.assertIn('loaded.optInt(ConfigPersistence.MARKER, 0) < ConfigPersistence.MIGRATION', load)
        self.assertLess(load.index('boolean migrate'), load.index('ConfigPersistence.dropRetiredDefaults(loaded)'))
        self.assertIn('if (migrate) {', load)
        # Every save stamps the marker, so a retired value chosen after migration is kept.
        self.assertIn('stored.put(ConfigPersistence.MARKER, ConfigPersistence.MIGRATION)', save)
        self.assertLess(save.index('ConfigPersistence.sparse('), save.index('stored.put(ConfigPersistence.MARKER'))

    def test_followup_window_retirement_matches_history(self):
        persistence = src('app/src/ConfigPersistence.java')
        self.assertIn('{"assistant_followup_seconds", "15"}', persistence)
        self.assertIn('.put("assistant_followup_seconds", 10)', src('app/src/CloudConfig.java'))

    def test_retired_value_is_not_the_current_default(self):
        current = src('app/src/CloudConfig.java')
        self.assertIn('.put("recording_max_minutes", 30)', current)
        self.assertIn('{"recording_max_minutes", "60"}', src('app/src/ConfigPersistence.java'))


class Stage0WeatherRetry(unittest.TestCase):
    """09-23 plan stage 0.3: a recording blocked the weather push and the lens temperature went
    1.5 hours stale because every non-success waited a full 30-minute period."""

    def test_rules(self):
        self.assertIn(b'weather retry checks passed',
                      run_check(['app/src/WeatherPolicy.java', 'app/src/WeatherRetry.java', 'tests/WeatherRetryCheck.java'], 'WeatherRetryCheck'))

    def test_busy_phrases_are_exactly_ensure_idles_refusals(self):
        idle = method(src('app/src/TodoGlassesClient.java'), 'private void ensureIdle(')
        busy = src('app/src/WeatherRetry.java')
        for phrase in ('上一项眼镜操作尚未完成', '请先结束当前录音', '请先结束当前语音问答', '请先退出眼镜当前阅读页'):
            self.assertIn(phrase, idle)
            self.assertIn('"%s"' % phrase, busy)
        # The in-process lock is the fifth busy refusal (review 09-23 M3).
        self.assertIn('另一项眼镜操作正在进行', src('app/src/TodoGlassesClient.java'))
        self.assertIn('"另一项眼镜操作正在进行"', busy)

    def test_busy_check_runs_before_the_expensive_fetch(self):
        refresh = method(src('app/src/ConnectionService.java'), 'private void refreshWeather(')
        self.assertLess(refresh.index('busyReason()'), refresh.index('new WeatherSync('))
        self.assertLess(refresh.index('WeatherRetry.deviceBusy(busy)'), refresh.index('newWakeLock('))

    def test_schedule_uses_the_busy_aware_delay(self):
        schedule = method(src('app/src/ConnectionService.java'), 'private void scheduleWeather(')
        self.assertIn('WeatherRetry.nextDelay(', schedule)
        # Stage 0.5: counted from the last push and attempt, not a full period from now.
        self.assertIn('w.getLong("last_sent",0)', schedule)
        self.assertIn('w.getLong("last_attempt_ms",0)', schedule)
        self.assertNotIn('long delay=1800000', schedule)

    def test_each_real_attempt_is_timestamped(self):
        refresh = method(src('app/src/ConnectionService.java'), 'private void refreshWeather(')
        self.assertIn('"last_attempt_ms"', refresh)
        self.assertLess(refresh.index('"last_attempt_ms"'), refresh.index('new WeatherSync('))
        self.assertIn('"last_busy_ms"', refresh)


class Stage05WeatherTriggers(unittest.TestCase):
    """09-23 plan stage 0.5: "30 分钟的后台更新太傻了" -- 15-minute period counted from the last
    push, plus reconnect / App-visible / 2 km move triggers, each gated by staleness."""

    def test_rules(self):
        self.assertIn(b'stage 0.5 policy checks passed',
                      run_check(['app/src/WeatherPolicy.java', 'app/src/ReconnectPolicy.java', 'tests/Stage05PolicyCheck.java'],
                                'Stage05PolicyCheck'))

    def test_retry_checks_see_the_policy(self):
        self.assertIn(b'weather retry checks passed',
                      run_check(['app/src/WeatherPolicy.java', 'app/src/WeatherRetry.java', 'tests/WeatherRetryCheck.java'],
                                'WeatherRetryCheck'))

    def test_period_has_one_definition(self):
        self.assertIn('WeatherPolicy.stale(last,now,WeatherPolicy.PERIOD_MS)', src('app/src/WeatherSync.java'))
        self.assertIn('PERIOD_MS = WeatherPolicy.PERIOD_MS', src('app/src/WeatherRetry.java'))
        for name in ('WeatherSync.java', 'WeatherRetry.java', 'ConnectionService.java'):
            self.assertNotIn('1800000', src('app/src/' + name), name)

    def test_every_reconnect_forces_one_push(self):
        # User 09-23: "只要它重新连接，就要强制刷新一次" -- no age gate.
        service = src('app/src/ConnectionService.java')
        ready = method(service, 'static void connectionReady(')
        self.assertIn('boolean becameReady=ready&&!service.deviceReady', ready)
        self.assertIn('postDelayed(service.readyTick,2000)', ready)
        # No age gate (maxAge 0 is always stale); only the spacing inside refreshIfOlder applies.
        self.assertIn('readyTick=()->refreshIfOlder(0)', service)
        self.assertNotIn('CONNECT_STALE', service)

    def test_visible_app_uses_foreground_threshold_and_manual_pushes_now(self):
        visible = method(src('app/src/ConnectionService.java'), 'static void weatherFromVisible(')
        self.assertIn('force?0:WeatherPolicy.FOREGROUND_STALE_MS', visible)
        promote = method(src('app/src/ConnectionService.java'), 'private void promoteWeather(')
        self.assertIn('if(maxAge<=0)refreshWeather(true);else refreshIfOlder(maxAge);', promote)
        self.assertLess(promote.index('locationForeground=true'), promote.index('watchMoves()'))

    def test_trigger_refresh_is_spaced_and_stale_gated(self):
        body = method(src('app/src/ConnectionService.java'), 'private void refreshIfOlder(')
        self.assertIn('WeatherPolicy.stale(w.getLong("last_sent",0),now,maxAge)', body)
        self.assertIn('WeatherPolicy.MIN_SPACING_MS', body)
        self.assertIn('refreshWeather(true)', body)
        # Round 3: a trigger inside the spacing retries when the spacing ends, not at the next period.
        self.assertLess(body.index('WeatherPolicy.stale(attempt,now,WeatherPolicy.MIN_SPACING_MS)'), body.index('postDelayed(spacedRetry,'))
        self.assertIn('WeatherPolicy.untilStale(attempt,now,WeatherPolicy.MIN_SPACING_MS)+1000', body)
        self.assertIn('spacedRetry=()->refreshIfOlder(spacedRetryAge)', src('app/src/ConnectionService.java'))
        self.assertIn('handler.removeCallbacks(spacedRetry)', method(src('app/src/ConnectionService.java'), 'private void stopWeather('))
        refresh = method(src('app/src/ConnectionService.java'), 'private void refreshWeather(')
        self.assertLess(refresh.index('handler.removeCallbacks(spacedRetry)'), refresh.index('new WeatherSync('))

    def test_move_watch_is_passive_and_released(self):
        service = src('app/src/ConnectionService.java')
        watch = method(service, 'private void watchMoves(')
        self.assertIn('LocationManager.PASSIVE_PROVIDER', watch)
        self.assertIn('WeatherPolicy.MOVE_METERS', watch)
        self.assertIn('catch(RuntimeException e)', watch)
        self.assertIn('unwatchMoves()', method(service, 'private void stopWeather('))

    def test_waiting_note_is_cleared_once_ready(self):
        service = src('app/src/ConnectionService.java')
        refresh = method(service, 'private void refreshWeather(')
        self.assertIn('clearWaiting();scheduleWeather();return;', refresh)
        self.assertIn('if(deviceReady)clearWaiting();scheduleWeather();', method(service, 'private void refreshIfOlder('))
        # Ready path: readyTick -> refreshIfOlder(0). Either it pushes -- refreshWeather(true), whose
        # WeatherSync.start() overwrites last_result with its first progress line before anything
        # else -- or it is inside the spacing and clears the note itself (asserted above).
        self.assertIn('if(!force&&!WeatherSync.due(this))', refresh)
        self.assertLess(refresh.index('if(!deviceReady)'), refresh.index('new WeatherSync('))
        start = method(src('app/src/WeatherSync.java'), 'void start(')
        self.assertIn('owns=true;feedback.show("正在获取手机大致位置…");', start)
        self.assertIn('if(!ACTIVE.compareAndSet(false,true)){feedback.show(', start)

    def test_move_watch_state_follows_the_listener(self):
        service = src('app/src/ConnectionService.java')
        self.assertIn('"move_watch","off"', method(service, 'private void unwatchMoves('))

    def test_user_facing_period_text_matches(self):
        self.assertNotIn('30分钟', src('app/src/WeatherActivity.java'))
        self.assertIn('约每15分钟', src('app/src/WeatherActivity.java'))
        self.assertNotIn('约每30分钟', src('docs/cn/FEATURES-0.2.1.md'))


class Stage05AutoReconnect(unittest.TestCase):
    """09-23 plan stage 0.5: three measured losses (overnight, link timeout while worn, fold and
    crown wake) all stayed disconnected although the system saw the glasses nearby."""

    def test_receiver_registered_not_exported(self):
        manifest = src('app/AndroidManifest.xml')
        self.assertIn('<receiver android:name=".AutoReconnect" android:exported="false" />', manifest)

    def test_drop_after_authentication_starts_a_round(self):
        complete = method(src('app/src/SdkProbeActivity.java'), 'private void complete(')
        hook = 'if (persistentSession && authenticated && "failed".equals(status) && !HOST_DESTROYED.equals(reason))'
        self.assertIn(hook, complete)
        self.assertIn('cleanupStep(() -> AutoReconnect.afterDrop(this, sessionReadyAtMs));', complete)
        # After the session lease is released, so the attempt is not refused as "session exists".
        self.assertLess(complete.index('sessions.release(this);'), complete.index(hook))
        # The constant must be what the host teardown passes, or the exclusion is dead.
        self.assertIn('if (!finished) complete("failed", HOST_DESTROYED);', method(src('app/src/SdkProbeActivity.java'), 'void hostDestroyed('))

    def test_backoff_is_not_reset_by_short_sessions_or_presence_flaps(self):
        receiver = src('app/src/AutoReconnect.java')
        drop = method(receiver, 'static void afterDrop(')
        self.assertIn('ReconnectPolicy.stable(sessionReadyAtMs, System.currentTimeMillis())', drop)
        self.assertIn('stable ? 0 : p.getInt("attempt", 0)', drop)
        self.assertNotIn('connected_ms', drop)
        # Review round 2: the ready time is this session's own, set only at its ready transition.
        activity = src('app/src/SdkProbeActivity.java')
        self.assertEqual(activity.count('sessionReadyAtMs='), 1)
        self.assertIn('private long sessionReadyAtMs;', activity)
        presence = method(receiver, 'static void onPresence(')
        self.assertLess(presence.index('p.getLong("next_ms", 0) > now'), presence.index('begin('))
        self.assertLess(presence.index('ReconnectPolicy.PRESENCE_QUIET_MS'), presence.index('begin('))
        # 09-23: with a round pending, "nearby" pulls the next attempt forward (count kept), spaced.
        pending = presence[presence.index('p.getLong("next_ms", 0) > now'):presence.index('ReconnectPolicy.PRESENCE_QUIET_MS')]
        self.assertIn('ReconnectPolicy.PRESENCE_MIN_GAP_MS', pending)
        self.assertIn('schedule(context, ReconnectPolicy.PRESENCE_RETRY_MS)', pending)
        self.assertNotIn('putInt("attempt"', pending)
        self.assertNotIn('putInt("attempt"', method(receiver, 'static void connected('))
        receive = method(receiver, 'private void run(Context context, int gen)')
        busy = receive[receive.index('if (SdkProbeActivity.connectionActive)'):]
        self.assertIn('putInt("attempt", attempt + 1)', busy[:busy.index('return;')])
        self.assertLess(receive.index('ReconnectPolicy.exhausted(attempt)'), receive.index('SdkProbeActivity.connectionActive'))

    def test_start_outcome_is_honest(self):
        receiver = src('app/src/AutoReconnect.java')
        self.assertIn('outcome = "start_requested";', receiver)
        self.assertNotIn('outcome = "started"', receiver)
        # The session stamps it only when an automatic attempt really started.
        self.assertIn('if(getIntent().getBooleanExtra("auto_reconnect",false))AutoReconnect.sessionStarted(this);',
                      method(src('app/src/SdkProbeActivity.java'), 'boolean start(Intent intent)'))
        self.assertIn('catch (RuntimeException e) { outcome = "start_refused:"', receiver)

    def test_ready_session_stops_the_round(self):
        activity = src('app/src/SdkProbeActivity.java')
        hook = 'if(persistentSession){sessionReadyAtMs=System.currentTimeMillis();AutoReconnect.connected(this);}'
        self.assertEqual(activity.count(hook), 1)
        # Inside the first transition to sdk_session_ready, next to stage("session_ready").
        i = activity.index(hook)
        self.assertIn('if (!result.optString("status").equals("sdk_session_ready")) {', activity[i - 1000:i])
        self.assertIn('stage("session_ready", true);', activity[i:i + 300])

    def test_user_disconnect_cancels(self):
        cloud = src('app/src/CloudActivity.java')
        self.assertIn('putBoolean("auto_connect",false).putLong("disconnect_ms",System.currentTimeMillis()).apply();AutoReconnect.stop(this);sendSessionCommand("stop",null);', cloud)

    def test_presence_triggers(self):
        appeared = method(src('app/src/CompanionPresence.java'), 'public void onDeviceAppeared(')
        self.assertIn('AutoReconnect.onPresence(this)', appeared)

    def test_attempt_respects_user_intent_and_existing_session(self):
        receive = method(src('app/src/AutoReconnect.java'), 'private void run(Context context, int gen)')
        launch = receive.index('ConnectionService.startSession(context,')
        self.assertLess(receive.index('!autoConnect(context)'), launch)
        self.assertLess(receive.index('SdkProbeActivity.connectionActive'), launch)
        self.assertLess(receive.index('ReconnectPolicy.exhausted(attempt)'), launch)
        self.assertNotIn('startActivity(', receive)
        # Same launch as the automatic "连接眼镜": never repair or force pairing mode.
        self.assertIn('.putExtra("repair_unbonded", false).putExtra("pairing_mode_attempt", false)', receive)
        self.assertIn('.putExtra("auto_reconnect", true)', receive)
        self.assertIn('if (!autoConnect(context)) {', receive)

    def test_in_process_timer_backs_up_the_deferred_alarm(self):
        receiver = src('app/src/AutoReconnect.java')
        schedule = method(receiver, 'private static void schedule(')
        self.assertIn('MAIN.postDelayed(TIMER, delayMs)', schedule)
        self.assertIn('setAndAllowWhileIdle', schedule)
        self.assertIn('MAIN.removeCallbacks(TIMER)', method(receiver, 'private static void cancel('))
        # Whichever fires first runs the attempt; the other is withdrawn or finds its period used.
        run = method(receiver, 'private void run(Context context, int gen)')
        self.assertLess(run.index('MAIN.removeCallbacks(TIMER);'), run.index('SharedPreferences p = prefs(context);'))
        # Review round 7: one attempt per scheduled period even if the alarm was already dispatched.
        self.assertIn('if (gen != p.getInt("gen", 0) || gen == p.getInt("done_gen", -1)) return;', run)
        self.assertLess(run.index('p.edit().putInt("done_gen", gen).apply();'), run.index('ConnectionService.startSession('))
        self.assertIn('run(context, intent == null ? -1 : intent.getIntExtra("gen", -1));', method(receiver, 'public void onReceive('))
        self.assertIn('new AutoReconnect().run(c, timerGen);', receiver)
        self.assertIn('int gen = p.getInt("gen", 0) + 1;', schedule)
        self.assertIn('alarm(context, gen)', schedule)
        self.assertIn('timerGen = gen;', schedule)
        # connected()/stop() void a period whose alarm may still be in flight.
        self.assertIn('putInt("done_gen", p.getInt("gen", 0))', method(receiver, 'private static void cancel('))

    def test_presence_survives_a_clock_step_back(self):
        presence = method(src('app/src/AutoReconnect.java'), 'static void onPresence(')
        self.assertIn('long since = now - p.getLong("last_attempt_ms", 0);', presence)
        self.assertIn('if (since < 0 || since >= ReconnectPolicy.PRESENCE_MIN_GAP_MS) {', presence)
        self.assertIn('if (since >= 0 && since < ReconnectPolicy.PRESENCE_QUIET_MS) return;', presence)


class Stage05SessionHostedByService(unittest.TestCase):
    """09-23 plan 0.5c: the session moved out of an Activity into the connectedDevice foreground
    service. As an Activity, MIUI refused background reconnects until the user granted "show on top",
    and Back on its debug page ended the link (both on device 09-23)."""

    def test_session_is_not_an_activity(self):
        session = src('app/src/SdkProbeActivity.java')
        self.assertIn('public final class SdkProbeActivity extends android.content.ContextWrapper {', session)
        for gone in ('extends Activity', 'import android.app.Activity;', 'getWindow()', 'setContentView', 'moveTaskToBack',
                     'startActivity(', 'finish()', 'onCreate(Bundle', 'onDestroy()'):
            self.assertNotIn(gone, session, gone)
        manifest = src('app/AndroidManifest.xml')
        self.assertNotIn('.SdkProbeActivity', manifest)
        self.assertIn('android:name=".ConnectionService" android:exported="false" android:foregroundServiceType="connectedDevice|location"', manifest)

    def test_start_claims_before_anything_else(self):
        start = method(src('app/src/SdkProbeActivity.java'), 'boolean start(Intent intent)')
        self.assertLess(start.index('startIntent = intent;'), start.index('sessions.claim(this)'))
        self.assertLess(start.index('sessions.claim(this)'), start.index('connectionActive=true'))
        self.assertLess(start.index('connectionActive=true'), start.index('begin();'))
        self.assertIn('return false;', start[:start.index('connectionActive=true')])

    def test_host_creates_one_session_at_a_time(self):
        service = src('app/src/ConnectionService.java')
        command = method(service, 'public int onStartCommand(')
        # Review round 7: every start command records its id and re-asserts foreground first.
        self.assertLess(command.index('lastStartId=id;holdForeground();'), command.index('ACTION_SESSION.equals(intent.getAction())'))
        self.assertLess(command.index('ACTION_SESSION.equals(intent.getAction())'), command.index('releaseIfIdle()'))
        self.assertIn('if(!startGate.hasOwner()&&session==null&&releaseIfIdle()){}', command)
        self.assertIn('stopSelfResult(lastStartId)', method(service, 'private void stopUnlessNewerStart('))
        hold = method(service, 'private void holdForeground(')
        self.assertIn('startForeground(21,', hold)
        # Review round 8: keep the current text (recording) and drop location once its permission is gone.
        self.assertIn('boolean location=locationForeground&&WeatherSync.enabled(this);', hold)
        self.assertIn('shownTitle!=null?shownTitle', hold)
        self.assertIn('"foreground_error"', hold)
        # Round 9: recorded only after a successful post; revoked location stops the weather as well.
        self.assertIn('builtTitle=title;builtText=text;', method(service, 'private static Notification notification('))
        # Every post that records the text calls shown() after it, in the same statement block.
        for post in ('notify(21,', 'startForeground(21,'):
            for chunk in service.split(post)[1:]:
                self.assertIn('shown();', chunk[:chunk.index(';}') + 2 if ';}' in chunk[:400] else 400], post)
        self.assertLess(hold.index('if(stopped){stopWeather();'), hold.index('startForeground(21,'))
        self.assertIn('定位权限已关闭，自动天气已暂停', hold)
        self.assertNotIn('stopSelf()', service)
        self.assertNotIn('stopService(', service)
        begin = method(service, 'private void beginSession(')
        self.assertLess(begin.index('if(session!=null&&!session.ended())'), begin.index('new SdkProbeActivity(this)'))
        self.assertLess(begin.index('boolean started=next.start(intent);'), begin.index('started?"started_ms":"refused_ms"'))
        self.assertIn('if(!started&&session==next){session=null;releaseIfIdle();}', begin)
        start = method(service, 'static void startSession(')
        self.assertIn('context.startForegroundService(intent);', start)
        self.assertIn('.setAction(ACTION_SESSION)', start)

    def test_ended_session_releases_the_host(self):
        service = src('app/src/ConnectionService.java')
        ended = method(service, 'static void sessionEnded(')
        self.assertIn('service.session!=ended', ended)
        self.assertIn('service.session=null;service.deviceReady=false;', ended)
        self.assertIn('if(!service.releaseIfIdle()){', ended)
        self.assertIn('service.stateNotification(service.locationForeground)', ended)
        # stop() only gives up the gate; the stop decision is made after a reconnect may be scheduled.
        stop = method(service, 'static void stop(Context context,Object owner)')
        self.assertIn('startGate.cancel(owner);', stop)
        self.assertNotIn('stopUnlessNewerStart', stop)
        complete = method(src('app/src/SdkProbeActivity.java'), 'private void complete(')
        self.assertLess(complete.index('sessions.release(this);'), complete.index('AutoReconnect.afterDrop(this, sessionReadyAtMs)'))
        self.assertLess(complete.index('AutoReconnect.afterDrop(this, sessionReadyAtMs)'), complete.index('ConnectionService.sessionEnded(this)'))

    def test_host_follows_demands_like_the_official_service(self):
        # 09-23: a service rebuilt from the background was refused the location type and the weather
        # stopped; the official RayneoForegroundService stops only when no demand is left.
        service = src('app/src/ConnectionService.java')
        idle = method(service, 'boolean releaseIfIdle(')
        self.assertIn('if(startGate.hasOwner()||(session!=null&&!session.ended())||AutoReconnect.pending(this))return false;', idle)
        self.assertIn('stopUnlessNewerStart();return true;', idle)
        self.assertIn('service.releaseIfIdle()', method(service, 'static void reconnectEnded('))
        receiver = src('app/src/AutoReconnect.java')
        pending = method(receiver, 'static boolean pending(')
        # Round 11: a next_ms left behind by a force-stop or reboot (alarms gone) is not a demand.
        self.assertIn('prefs(context).contains("next_ms")', pending)
        self.assertIn('PendingIntent.FLAG_NO_CREATE', pending)
        self.assertIn('new Intent(context, AutoReconnect.class)', pending)
        # Round 12: the query must use the same requestCode as the alarm it stands for.
        self.assertIn('autoConnect(context)', pending)
        self.assertIn('PendingIntent.getBroadcast(context, 35,', pending)
        self.assertIn('PendingIntent.getBroadcast(context, 35,', method(receiver, 'private static PendingIntent alarm('))
        ended = method(receiver, 'private static void ended(')
        self.assertIn('.remove("next_ms")', ended)
        self.assertIn('ConnectionService.reconnectEnded();', ended)
        run = method(receiver, 'private void run(Context context, int gen)')
        for outcome in ('"user_disconnect"', '"gave_up"'):
            self.assertIn('ended(context, %s)' % outcome, run)
        self.assertIn('{ ended(context, outcome); return; }', run)
        self.assertIn('ConnectionService.reconnectEnded();', method(receiver, 'static void stop(Context context)'))
        # One place maps state to text: waiting to reconnect is never shown as connected (round 11).
        state = method(service, 'private Notification stateNotification(')
        self.assertLess(state.index('if(session==null&&!deviceReady&&AutoReconnect.pending(this))return notification(this,"眼镜连接中断"'),
                        state.index('"眼镜已连接 · 自动天气"'))
        for where in ('static void connectionReady(', 'static void sessionEnded(', 'private void promoteWeather(', 'static void disableWeather('):
            self.assertIn('stateNotification(', method(service, where), where)
        self.assertNotIn('notification(this,"眼镜已连接 · 自动天气"', method(service, 'private void promoteWeather('))
        self.assertIn('后台无法启用定位；打开 App 后自动恢复天气更新', method(service, 'private void promoteWeather('))
        self.assertIn('catch(RuntimeException|Error e){startGate.cancel(owner);throw e;}', method(service, 'static void start(Context context,Object owner)'))
        destroy = method(service, 'public void onDestroy(')
        self.assertLess(destroy.index('open.hostDestroyed()'), destroy.index('running = false'))

    def test_every_launcher_uses_the_service(self):
        cloud = src('app/src/CloudActivity.java')
        self.assertIn('ConnectionService.startSession(this,new Intent().putExtra("target_address",address)', cloud)
        self.assertNotIn('SdkProbeActivity.class', cloud)
        self.assertNotIn('SdkProbeActivity.class', src('app/src/AutoReconnect.java'))
        self.assertIn("'am', 'start-foreground-service', '-n', PACKAGE + '/.ConnectionService'", src('lab.py'))

    def test_companion_fgs_permission_spelled_as_the_platform(self):
        manifest = src('app/AndroidManifest.xml')
        self.assertIn('android.permission.REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND', manifest)
        self.assertNotIn('REQUEST_COMPANION_START_FOREGROUND_SERVICE_FROM_BACKGROUND', manifest)
        self.assertIn('android.permission.REQUEST_COMPANION_RUN_IN_BACKGROUND', manifest)



class Stage05WeatherLocation(unittest.TestCase):
    """09-23 17:09: after a background reconnect a fresh network fix gave nothing in 30 s (3 s in
    front) and the weather push timed out; the entry page silently cleared auto_connect; the official
    app holds ACCESS_BACKGROUND_LOCATION and we did not declare it."""

    def test_location_order_known_fused_network_saved(self):
        weather = src('app/src/PhoneWeather.java')
        locate = method(weather, 'void locate(Callback callback)')
        self.assertLess(locate.index('recentKnown(manager)'), locate.index('getCurrentLocation') if 'getCurrentLocation' in locate else locate.index('next(manager,providers,0,callback)'))
        self.assertIn('for(String name:new String[]{"fused",LocationManager.NETWORK_PROVIDER})', locate)
        self.assertIn('failOrSaved(callback,"定位等待超时', locate)
        self.assertIn('failOrSaved(callback,"本次未取得手机位置', method(weather, 'private void next('))
        known = method(weather, 'private Location recentKnown(')
        self.assertIn('age>=0&&age<=RECENT_NS', known)
        self.assertIn('RECENT_NS = 10L * 60 * 1_000_000_000L', weather)
        saved = method(weather, 'private void failOrSaved(')
        self.assertIn('age>=0&&age<=SAVED_MS', saved)
        self.assertIn('new Location("saved_last_fix")', saved)
        self.assertIn('SAVED_MS = 3L * 60 * 60 * 1000', weather)
        # Permission or location-off failures never fall back to a saved position.
        for refusal in ('请先开启手机定位服务', '请允许使用大致位置'):
            self.assertNotIn(refusal, saved)
        # Only the ~1 km coordinates sent to the weather service are kept.
        self.assertIn('Math.round(l.getLatitude()*100)/100.0', method(weather, 'private void remember('))

    def test_entry_page_leaves_auto_connect_alone(self):
        lab_page = src('app/src/SdkLabActivity.java')
        self.assertIsNone(re.search(r'putBoolean\(\s*"auto_connect"\s*,\s*false\s*\)', lab_page))
        self.assertIn('putBoolean("auto_connect",false)', src('app/src/CloudActivity.java'))  # only "断开眼镜"

    def test_background_location_like_the_official_app(self):
        self.assertIn('android.permission.ACCESS_BACKGROUND_LOCATION', src('app/AndroidManifest.xml'))
        page = src('app/src/WeatherActivity.java')
        ask = method(page, 'private void askBackgroundLocation(')
        self.assertLess(ask.index('ACCESS_COARSE_LOCATION'), ask.index('requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},74)'))
        self.assertIn('"bg_location_asked"', method(page, 'private void enable('))
        self.assertIn('允许后台定位（始终允许）', page)
        self.assertIn('if(request==74){', page)
        # Round 13: the real permission is read on every start and kept; the fallback re-checks access.
        self.assertIn('recordBackgroundLocation();handler.post(refreshStatus);', page)
        self.assertIn('"bg_location_granted"', method(page, 'private void recordBackgroundLocation('))
        saved = method(src('app/src/PhoneWeather.java'), 'private void failOrSaved(')
        self.assertLess(saved.index('isLocationEnabled()'), saved.index('new Location("saved_last_fix")'))
        self.assertLess(saved.index('ACCESS_COARSE_LOCATION'), saved.index('new Location("saved_last_fix")'))
        self.assertIn('l.getTime()', method(src('app/src/PhoneWeather.java'), 'private void remember('))
        self.assertIn('"location_type_error"', src('app/src/ConnectionService.java'))


class Stage05dConnectionTail(unittest.TestCase):
    """09-23 plan 0.5d: reconnect after a phone restart; battery "不限制" asked in-app (both as the official app)."""

    def test_boot_starts_a_round_unless_user_disconnected(self):
        manifest = src('app/AndroidManifest.xml')
        self.assertIn('android.permission.RECEIVE_BOOT_COMPLETED', manifest)
        self.assertIn('<receiver android:name=".BootReconnect" android:exported="false">', manifest)
        self.assertIn('android.intent.action.BOOT_COMPLETED', manifest)
        boot = src('app/src/BootReconnect.java')
        # Not exported: the system server (uid 1000) still delivers BOOT_COMPLETED; other actions are ignored.
        self.assertIn('!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;', boot)
        self.assertIn('AutoReconnect.afterBoot(context);', boot)
        after = method(src('app/src/AutoReconnect.java'), 'static void afterBoot(')
        # The stale next_ms names an alarm lost with the restart; begin() checks auto_connect.
        self.assertLess(after.index('remove("next_ms")'), after.index('begin(context, "boot", 0);'))
        self.assertIn('if (!autoConnect(context)) return;', method(src('app/src/AutoReconnect.java'), 'private static void begin('))

    def test_battery_guide_asks_once_after_explicit_connect(self):
        self.assertIn('android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS', src('app/AndroidManifest.xml'))
        guide = src('app/src/BatteryGuide.java')
        self.assertIn('isIgnoringBatteryOptimizations(context.getPackageName())', guide)
        self.assertIn('Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS', guide)
        self.assertIn('Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS', guide)
        once = method(guide, 'static void askOnce(')
        # Asked once: the guard reads the flag before asking, and the flag is set only when a page opened.
        self.assertIn('if (p.getBoolean("battery_asked", false) || unrestricted(activity)) return;', once)
        self.assertLess(once.index('p.getBoolean("battery_asked", false)'), once.index('ask(activity)'))
        self.assertIn('if (ask(activity) == OPENED) p.edit().putBoolean("battery_asked", true).apply();', once)
        # "Already unrestricted" is not claimed when no settings page exists (review 09-23 1-B).
        ask = method(guide, 'static int ask(')
        self.assertIn('catch (RuntimeException ignored) { return UNAVAILABLE; }', ask)
        self.assertIn('if(r==BatteryGuide.ALREADY)status.setText("电池优化已是不限制")', src('app/src/WeatherActivity.java'))
        connect = method(src('app/src/CloudActivity.java'), 'private void connectGlasses(boolean automatic, boolean explicitPairing)')
        self.assertIn('if(!automatic)BatteryGuide.askOnce(this);', connect)
        self.assertIn('BatteryGuide.ask(this)', src('app/src/WeatherActivity.java'))

    def test_disconnect_while_connecting_is_not_undone_by_ready(self):
        # Review 09-23 (0.5d) item 2: the ready transition re-enabled auto_connect unconditionally.
        activity = src('app/src/SdkProbeActivity.java')
        self.assertIn('if(persistentSession&&assistantPrefs().getLong("disconnect_ms",0)<startedWallMs)assistantPrefs().edit().putBoolean("auto_connect",true).apply();', activity)
        self.assertEqual(activity.count('putBoolean("auto_connect",true)'), 1)
        self.assertIn('startedWallMs = System.currentTimeMillis();', method(activity, 'boolean start(Intent intent)'))
        self.assertIn('.putBoolean("auto_connect",false).putLong("disconnect_ms",System.currentTimeMillis()).apply();AutoReconnect.stop(this);', src('app/src/CloudActivity.java'))

if __name__ == '__main__':
    unittest.main()
