package dev.xr.rayneo.probe;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

/** Hosts the glasses session and keeps it foreground when the screen turns off.
 * Optional weather refresh owns a location foreground type and a bounded wake lock.
 * The connection owns Bluetooth and stops this service on explicit disconnect or failure.
 *
 * <p>Since 2026-09-23 (plan 0.5c) the session itself lives here: {@link #startSession} starts this
 * service with the session's parameters and it creates one {@link SdkProbeActivity} object per
 * session. Before, the session was an Activity: a background reconnect needed MIUI's "show on top"
 * permission and Back on its page ended the link. A companion-device app may start a foreground
 * service from the background (REQUEST_COMPANION_RUN_IN_BACKGROUND / ..._SERVICES_FROM_BACKGROUND).
 */
public final class ConnectionService extends Service {
    static volatile boolean running;
    private static ConnectionService current;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private WeatherSync weather;
    private Object weatherOwner;
    private PowerManager.WakeLock weatherWake;
    private boolean locationForeground;
    private boolean deviceReady;
    private final Runnable weatherTick=()->refreshWeather(false);
    // User 09-23: "只要它重新连接，就要强制刷新一次" -- no age gate on reconnect; only the 2-minute
    // spacing from the last real attempt, so a flapping ready signal cannot push every few seconds.
    private final Runnable readyTick=()->refreshIfOlder(0);
    private LocationListener moves;
    private static final ServiceStartGate startGate=new ServiceStartGate();
    static final String ACTION_SESSION="dev.xr.rayneo.probe.SESSION";
    private SdkProbeActivity session;
    /** Starts a session with the given parameters (the extras "连接眼镜" always used). Throws what
     * startForegroundService throws, e.g. ForegroundServiceStartNotAllowedException. */
    static void startSession(Context context,Intent parameters){
        Intent intent=new Intent(context,ConnectionService.class).setAction(ACTION_SESSION);
        if(parameters.getExtras()!=null)intent.putExtras(parameters.getExtras());
        context.startForegroundService(intent);
    }
    /** Called by the session when it has ended (after it scheduled any reconnect). The host keeps
     * running while a reconnect is pending -- see releaseIfIdle. */
    static void sessionEnded(SdkProbeActivity ended){
        ConnectionService service=current;if(service==null||service.session!=ended)return;
        service.session=null;service.deviceReady=false;
        if(!service.releaseIfIdle()){
            // Waiting to reconnect: say so instead of claiming a connection.
            try{service.getSystemService(NotificationManager.class).notify(21,service.stateNotification(service.locationForeground));shown();}catch(RuntimeException ignored){}
        }
    }
    /** Demand aggregation, as the official app's RayneoForegroundService does (connected/dataSync/location are separate demands; it stops only when none is left). Here the
     * demands are: a session, its start-gate owner, and a pending automatic reconnect. Keeping the
     * service through a reconnect keeps the location type granted while the App was in front -- on
     * 09-23 a service rebuilt from the background was refused that type and the weather stopped.
     * Returns true when it asked to stop. */
    boolean releaseIfIdle(){
        if(startGate.hasOwner()||(session!=null&&!session.ended())||AutoReconnect.pending(this))return false;
        stopUnlessNewerStart();return true;
    }
    /** The notification for the current state: waiting to reconnect is never shown as connected
     * (review round 11: promoteWeather overwrote "连接中断" with "已连接 · 自动天气"). */
    private Notification stateNotification(boolean location){
        if(session==null&&!deviceReady&&AutoReconnect.pending(this))return notification(this,"眼镜连接中断","正在自动重连；可在 App 中断开");
        return location?notification(this,"眼镜已连接 · 自动天气","保持蓝牙连接，定期使用手机位置更新天气。")
            :notification(this,"眼镜已连接","可在 App 中开启录音或语音待命。");
    }
    /** For AutoReconnect: a round ended (gave up, user disconnected, nothing to connect to). */
    static void reconnectEnded(){ConnectionService service=current;if(service!=null&&running&&service.session==null)service.releaseIfIdle();}
    /** Review 09-23 round 7: a "connect" queued behind a failing session must not be started on an
     * instance that is about to be destroyed (it would die at once as HOST_DESTROYED, no retry).
     * stopSelfResult(latest id) declines to stop while a newer start command is pending. */
    private int lastStartId;
    private void stopUnlessNewerStart(){stopSelfResult(lastStartId);}
    /** Every start command re-asserts foreground, so no platform can count a startForegroundService on
     * the running service as unanswered (review round 7: only onCreate called startForeground). */
    private void holdForeground(){
        // Keep whatever the notification already says (e.g. "眼镜正在录音"); location type only while
        // the permission still holds, or startForeground throws (review round 8).
        boolean location=locationForeground&&WeatherSync.enabled(this);
        // Location permission gone while the weather ran: stop the weather too, so no timer later
        // tries to locate under a foreground type that no longer includes location (review round 9).
        boolean stopped=locationForeground&&!location;
        if(stopped){stopWeather();getSharedPreferences("weather",0).edit().putString("last_result","定位权限已关闭，自动天气已暂停；重新允许后打开 App 即恢复").apply();}
        String title=!stopped&&shownTitle!=null?shownTitle:"眼镜连接服务",text=!stopped&&shownText!=null?shownText:"保持蓝牙连接；语音待命状态可在 App 中查看。";
        try{startForeground(21,notification(this,title,text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE|(location?ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION:0));shown();}
        catch(RuntimeException e){getSharedPreferences("session_host",0).edit().putString("foreground_error",e.getClass().getSimpleName()).putLong("foreground_error_ms",System.currentTimeMillis()).apply();}
    }
    private void beginSession(Intent intent){
        SharedPreferences host=getSharedPreferences("session_host",0);
        if(session!=null&&!session.ended()){
            // One link at a time: a second "connect" while one runs is recorded, not started.
            host.edit().putLong("ignored_ms",System.currentTimeMillis()).apply();return;
        }
        SdkProbeActivity next=new SdkProbeActivity(this);session=next;
        boolean started=next.start(intent);
        host.edit().putLong(started?"started_ms":"refused_ms",System.currentTimeMillis()).putBoolean("auto_reconnect",intent.getBooleanExtra("auto_reconnect",false)).apply();
        if(!started&&session==next){session=null;releaseIfIdle();}
    }
    static void start(Context context,Object owner){
        startGate.request(owner);
        try{context.startForegroundService(new Intent(context,ConnectionService.class));}
        catch(RuntimeException|Error e){startGate.cancel(owner);throw e;}
    }
    /** The session gives up its start-gate ownership. Whether the host stops is decided in
     * sessionEnded, after a reconnect may have been scheduled (demand aggregation). If creation is
     * still queued, onStartCommand stops the now-promoted orphan instead. */
    static void stop(Context context,Object owner){
        startGate.cancel(owner);
    }
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("glasses_connection", "眼镜连接", NotificationManager.IMPORTANCE_LOW));
        startForeground(21, notification(this,"眼镜连接服务","保持蓝牙连接；语音待命状态可在 App 中查看。"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);shown();
        running = true;
        current=this;
    }
    /** What notification 21 last said. Recorded by shown() only after a post succeeded, so a refused
     * startForeground cannot leave a text that was never on screen (review round 9). */
    private static volatile String shownTitle,shownText,builtTitle,builtText;
    private static void shown(){shownTitle=builtTitle;shownText=builtText;}
    private static Notification notification(Context context,String title,String text){
        builtTitle=title;builtText=text;
        PendingIntent open = PendingIntent.getActivity(context, 0, new Intent(context, CloudActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(context, "glasses_connection")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("AIX IO · 眼镜连接中")
            .setContentTitle(title).setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build();
    }
    static void recordingState(Context context,String phase){
        if(!running)return;
        boolean active=phase.equals("starting")||phase.equals("recording")||phase.equals("stopping")||phase.equals("saving");
        String title=phase.equals("recording")?"眼镜正在录音":phase.equals("starting")?"正在开启眼镜录音":active?"正在保存眼镜录音":"眼镜已连接";
        try{context.getSystemService(NotificationManager.class).notify(21,notification(context,title,active?"音频仅存手机；点击打开录音控制。":"可在 App 中开启录音或语音待命。"));shown();}catch(SecurityException ignored){}
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        lastStartId=id;holdForeground();
        if(intent!=null&&ACTION_SESSION.equals(intent.getAction())){beginSession(intent);return START_NOT_STICKY;}
        if(!startGate.hasOwner()&&session==null&&releaseIfIdle()){}
        else if(WeatherSync.enabled(this))promoteWeather(WeatherPolicy.PERIOD_MS);
        return START_NOT_STICKY;
    }
    static void weatherFromVisible(Context context,boolean force){
        ConnectionService service=current;
        if(service==null||!running){context.getSharedPreferences("weather",0).edit().putString("last_result","请先连接眼镜；连接后自动开启天气更新").apply();return;}
        // Manual refresh pushes now; the App merely becoming visible pushes only a reading older than 10 minutes.
        service.promoteWeather(force?0:WeatherPolicy.FOREGROUND_STALE_MS);
    }
    /** Official-model todo sync (09-23 stage 1): once after every ready, and after the glasses
     * report a change. Runs through the same serial glasses path as the page; when the glasses are
     * busy it looks again every 20 s, up to 6 times, and records why in "todo_sync". */
    private final Runnable todoTick=this::runTodoSync;
    private int todoRetries;
    static void requestTodoSync(Context context,long delayMs){
        ConnectionService service=current;if(service==null||!running)return;
        service.handler.removeCallbacks(service.todoTick);service.todoRetries=0;service.handler.postDelayed(service.todoTick,delayMs);
    }
    private void runTodoSync(){
        if(!running||!deviceReady||!getPackageName().equals("dev.xr.rayneo.sdklab"))return;
        SharedPreferences t=getSharedPreferences("todo_sync",0);
        TodoGlassesClient client=new TodoGlassesClient(this);String busy=client.busy()?"另一项眼镜操作正在进行":client.busyReason();
        if(busy!=null){
            t.edit().putString("last_result",busy+"；稍后自动同步待办").putLong("last_busy_ms",System.currentTimeMillis()).apply();
            if(todoRetries++<6)handler.postDelayed(todoTick,20000);return;
        }
        t.edit().putLong("last_attempt_ms",System.currentTimeMillis()).apply();
        client.fullSync(new TodoGlassesClient.Callback(){
            public void progress(String value){}
            public void complete(TodoGlassesClient.Snapshot value,String message){
                t.edit().putString("last_result",message).putLong("last_ok_ms",System.currentTimeMillis()).putInt("last_items",value.items.size()).apply();
            }
            public void failed(String message){
                t.edit().putString("last_result",message).putLong("last_failed_ms",System.currentTimeMillis()).apply();
                // Busy-like refusals are retried; a failed read-back is not (review 09-23).
                boolean busy=message.contains("正在进行")||message.contains("尚未完成")||message.contains("请先")||message.contains("拒绝");
                if(busy&&todoRetries++<6)handler.postDelayed(todoTick,20000);
            }
        });
    }
    static void connectionReady(boolean ready){
        ConnectionService service=current;if(service==null)return;
        boolean becameReady=ready&&!service.deviceReady;service.deviceReady=ready;
        if(becameReady){
            // Back from "连接中断": the notification says connected again.
            try{service.getSystemService(NotificationManager.class).notify(21,service.stateNotification(service.locationForeground));shown();}catch(RuntimeException ignored){}
        }
        // Glasses back (reconnect): push once, whatever the age of the last reading.
        if(becameReady&&service.locationForeground){service.handler.removeCallbacks(service.readyTick);service.handler.postDelayed(service.readyTick,2000);}
        // Todo list after the weather push (the official app pushed its table ~24 s after connecting).
        if(becameReady)requestTodoSync(service,15000);
    }
    /** maxAge 0 = the user asked: push now. Otherwise push only a reading at least maxAge old. */
    private void promoteWeather(long maxAge){
        if(!WeatherSync.enabled(this))return;
        try{
            startForeground(21,stateNotification(true),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE|ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);shown();
            locationForeground=true;watchMoves();
            if(maxAge<=0)refreshWeather(true);else refreshIfOlder(maxAge);
        }catch(RuntimeException e){
            // Android 14 refuses the location type to a service (re)built from the background (09-23);
            // the user turned nothing off. The App coming to the front calls promoteWeather again.
            getSharedPreferences("weather",0).edit().putString("last_result","后台无法启用定位；打开 App 后自动恢复天气更新")
                .putString("location_type_error",e.getClass().getSimpleName()+": "+e.getMessage()).putLong("location_type_error_ms",System.currentTimeMillis()).apply();
        }
    }
    private PendingIntent weatherAlarm(){return PendingIntent.getBroadcast(this,33,new Intent(this,WeatherAlarm.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    private void scheduleWeather(){
        handler.removeCallbacks(weatherTick);
        if(!WeatherSync.enabled(this)||!locationForeground)return;
        // Inexact alarm: no exact-alarm permission; Android power management can defer it.
        // Busy glasses (recording, voice round, pending command) get a short retry, not a full period;
        // otherwise the timer runs out when the last push turns 15 minutes old (WeatherPolicy).
        SharedPreferences w=getSharedPreferences("weather",0);
        long delay=WeatherRetry.nextDelay(w.getString("last_result",""),w.getLong("last_sent",0),w.getLong("last_attempt_ms",0),System.currentTimeMillis());
        w.edit().putLong("next_check_ms",System.currentTimeMillis()+delay).apply();handler.postDelayed(weatherTick,delay);
        getSystemService(AlarmManager.class).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,SystemClock.elapsedRealtime()+delay,weatherAlarm());
    }
    private void refreshWeather(boolean force){
        if(!running||!startGate.hasOwner()||!locationForeground||!WeatherSync.enabled(this)||weather!=null)return;
        if(!deviceReady){getSharedPreferences("weather",0).edit().putString("last_result","等待眼镜连接完成，随后自动更新天气").apply();return;}
        if(!force&&!WeatherSync.due(this)){clearWaiting();scheduleWeather();return;}
        // Checked before the location fix and network fetch: while the glasses are busy only this
        // file read repeats (every BUSY_RETRY_MS), and the push goes out within a minute of them
        // becoming free instead of up to 30 minutes later (09-23 temperature stall).
        String busy=new TodoGlassesClient(this).busyReason();
        if(WeatherRetry.deviceBusy(busy)){getSharedPreferences("weather",0).edit().putString("last_result",busy+"；天气将在眼镜空闲后自动补推").putLong("last_busy_ms",System.currentTimeMillis()).apply();scheduleWeather();return;}
        // Timestamp of each real attempt, so "pushed within 60 s of the recording ending" is measurable.
        getSharedPreferences("weather",0).edit().putLong("last_attempt_ms",System.currentTimeMillis()).apply();
        // A real attempt supersedes any pending trigger retry (review 09-23 round 4: else a second push ~2 min later).
        handler.removeCallbacks(weatherTick);handler.removeCallbacks(spacedRetry);getSystemService(AlarmManager.class).cancel(weatherAlarm());
        weatherWake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,getPackageName()+":weather");weatherWake.acquire(120000);
        final Object owner=new Object();weatherOwner=owner;
        weather=new WeatherSync(this,new WeatherSync.Feedback(){
            public void show(String value){if(weatherOwner==owner)getSharedPreferences("weather",0).edit().putString("last_result",value).apply();}
            public void finished(){if(weatherOwner!=owner)return;weatherOwner=null;weather=null;releaseWeatherWake();if(running&&current==ConnectionService.this)scheduleWeather();}
        });
        weather.start();
    }
    /** Trigger-started refresh: only when the lens reading is at least maxAge old, and never within
     * MIN_SPACING_MS of the previous real attempt (a failing provider is not hammered). */
    private void refreshIfOlder(long maxAge){
        if(!running||!locationForeground||!WeatherSync.enabled(this))return;
        SharedPreferences w=getSharedPreferences("weather",0);long now=System.currentTimeMillis();
        long attempt=w.getLong("last_attempt_ms",0);
        if(!WeatherPolicy.stale(w.getLong("last_sent",0),now,maxAge)){if(deviceReady)clearWaiting();scheduleWeather();return;}
        if(WeatherPolicy.stale(attempt,now,WeatherPolicy.MIN_SPACING_MS)){refreshWeather(true);return;}
        // Due but inside the spacing (e.g. a reconnect right after a failed attempt): retry when the
        // spacing ends, not at the next period -- otherwise "every reconnect pushes once" is lost
        // for up to 15 minutes (review 09-23 round 3).
        scheduleWeather();
        handler.removeCallbacks(spacedRetry);spacedRetryAge=maxAge;
        handler.postDelayed(spacedRetry,WeatherPolicy.untilStale(attempt,now,WeatherPolicy.MIN_SPACING_MS)+1000);
    }
    private long spacedRetryAge;
    private final Runnable spacedRetry=()->refreshIfOlder(spacedRetryAge);
    /** The "waiting for the glasses" note must not outlive the wait (09-23: it stayed on screen while connected). */
    private void clearWaiting(){
        SharedPreferences w=getSharedPreferences("weather",0);
        if(w.getString("last_result","").startsWith("等待眼镜连接完成"))w.edit().putString("last_result","眼镜已连接；天气将按计划自动更新").apply();
    }
    /** Passive location: rides on fixes other apps already requested, no extra GPS. A 2 km move pushes. */
    private void watchMoves(){
        if(moves!=null)return;
        LocationListener listener=new LocationListener(){public void onLocationChanged(Location location){
            getSharedPreferences("weather",0).edit().putLong("moved_ms",System.currentTimeMillis()).apply();
            refreshIfOlder(0);
        }};
        try{
            getSystemService(LocationManager.class).requestLocationUpdates(LocationManager.PASSIVE_PROVIDER,WeatherPolicy.MIN_SPACING_MS,WeatherPolicy.MOVE_METERS,listener,Looper.getMainLooper());
            moves=listener;getSharedPreferences("weather",0).edit().putString("move_watch","on").apply();
        }catch(RuntimeException e){getSharedPreferences("weather",0).edit().putString("move_watch","refused:"+e.getClass().getSimpleName()).apply();}
    }
    private void unwatchMoves(){
        LocationListener listener=moves;moves=null;
        if(listener!=null){try{getSystemService(LocationManager.class).removeUpdates(listener);}catch(RuntimeException ignored){}
            getSharedPreferences("weather",0).edit().putString("move_watch","off").apply();}
    }
    private void releaseWeatherWake(){if(weatherWake!=null&&weatherWake.isHeld())weatherWake.release();weatherWake=null;}
    static void disableWeather(Context context){
        ConnectionService service=current;if(service==null)return;
        service.stopWeather();
        try{service.startForeground(21,service.stateNotification(false),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);shown();}catch(RuntimeException ignored){}
        context.getSharedPreferences("weather",0).edit().putString("last_result","已关闭自动天气；已发送的天气保持不变").apply();
    }
    private void stopWeather(){
        locationForeground=false;handler.removeCallbacks(weatherTick);handler.removeCallbacks(readyTick);handler.removeCallbacks(spacedRetry);unwatchMoves();getSystemService(AlarmManager.class).cancel(weatherAlarm());
        WeatherSync owned=weather;weather=null;weatherOwner=null;if(owned!=null)owned.close();releaseWeatherWake();
    }
    public static final class WeatherAlarm extends BroadcastReceiver {
        @Override public void onReceive(Context context,Intent intent){ConnectionService service=current;if(service!=null&&running)service.refreshWeather(false);}
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { SdkProbeActivity open=session;session=null;if(open!=null)open.hostDestroyed();
        running = false;stopWeather();if(current==this)current=null; stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy(); }
}
