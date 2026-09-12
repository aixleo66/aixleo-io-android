package dev.xr.rayneo.probe;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.*;
import android.widget.TextView;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

/** Foreground, one-target SDK discovery / first guest BLE authentication experiment. */
public final class SdkProbeActivity extends Activity {
    private static final SessionOwnership sessions = new SessionOwnership();
    static volatile boolean connectionActive;
    private boolean connectionServiceStarted;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final JSONObject result = new JSONObject();
    private final JSONArray stages = new JSONArray();
    private VendorRuntime runtime;
    private VendorMessageBridge messageBridge;
    private VendorChannelObserver channelObserver;
    private VendorRouteObserver routeObserver;
    private BluetoothManager manager;
    private Object listener, targetDevice;
    private List<?> listeners;
    private TextView display;
    private String address;
    private String businessProbe;
    private boolean authenticated;
    private boolean statusQuerySent, textAttempted;
    private String notificationUid;
    private JSONObject customNotification;
    private int sessionSeconds;
    private boolean persistentSession;
    private long nextStandbyAttempt;
    private SharedPreferences assistantPrefs(){return getSharedPreferences("assistant",MODE_PRIVATE);}
    private void beginStandby(String id)throws Exception{
        JSONObject settings=CloudConfig.load(this);
        if(settings.optString("dashscope_key").isEmpty()||(settings.optString("assistant_provider","deepseek").equals("knowledge")?settings.optString("knowledge_token").isEmpty():settings.optString("deepseek_key").isEmpty())){
            result.getJSONObject("last_command").put("status","failed");stage("standby_missing_config",true);return;
        }
        standbyEnabled=true;standbyReady=false;standbyRounds=0;standbyControl=id;
        cloudVoice=true;nativeReply=true;voiceCommand=id;voiceArmed=false;voiceRecording=false;
        voicePackets=0;voiceBytes=0;voiceAfterStop=0;OpusAudio.clear(voiceAudio);
        result.put("voice_test",new JSONObject().put("phase","preparing").put("audio_saved",false).put("audio_uploaded",false)
            .put("cloud_enabled",true).put("audio_source","glasses").put("trigger","glasses_wakeup"));
        publishStandby("preparing");prepareGlassesVoice(id);
    }
    private void restoreStandbyIfNeeded()throws Exception{
        if(!persistentSession||!assistantPrefs().getBoolean("auto_standby",true)||standbyEnabled||voiceArmed||voicePreparing||voiceRecording
            ||recordingPreparing||(recorder!=null&&recorder.busy())||SystemClock.elapsedRealtime()<nextStandbyAttempt
            ||SystemClock.elapsedRealtime()<nativeDisplayUntil)return;
        JSONObject prior=result.optJSONObject("last_command");
        if(prior!=null&&"pending".equals(prior.optString("status")))return;
        nextStandbyAttempt=SystemClock.elapsedRealtime()+60000;
        activeCommand=UUID.randomUUID().toString();activeCommandKind="voice-standby";
        result.put("last_command",new JSONObject().put("id",activeCommand).put("kind",activeCommandKind).put("status","pending"));
        stage("standby_auto_restore",true);beginStandby(activeCommand);
    }
    private boolean reconnectMode, pairingRequested;
    private boolean voiceArmed;
    private boolean voicePreparing;
    private volatile boolean voiceRecording, cloudVoice;
    private final List<byte[]> voiceAudio = new ArrayList<>();
    private final java.util.concurrent.ExecutorService voiceWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private int voicePackets, voiceBytes, voiceAfterStop;
    private String voiceCommand;
    private boolean nativeReply;
    private long voiceStopAt;
    private long nativeDisplayUntil;
    private final PhoneNotifications.Sink phoneNotificationSink=this::forwardPhoneNotification;
    private boolean forwardPhoneNotification(JSONObject payload,String id)throws Exception{
        JSONObject command=result.optJSONObject("last_command");
        if(finished||finishing||!authenticated||voiceRecording||voicePreparing||recordingPreparing||(recorder!=null&&recorder.busy())
            ||(command!=null&&"pending".equals(command.optString("status")))||(standbyEnabled&&!standbyReady)
            ||SystemClock.elapsedRealtime()<nativeDisplayUntil)return false;
        notificationUid=payload.optString("notificationUID");
        result.remove("notification_reply");
        sendBusiness("NOTIFICATION",2,payload,id);return true;
    }
    private boolean standbyEnabled, standbyReady;
    private int standbyRounds;
    private String standbyControl;
    private CloudClient.Cancellation cloudCancellation;
    private StreamingAsr streamingAsr;
    private GlassesRecorder recorder;
    private boolean recordingTransportOwned;
    private boolean recordingPreparing;
    private long recordingTransportGeneration;
    private String recordingNotificationPhase="";
    private void prepareRecordingTask() throws Exception {
        nextStandbyAttempt=0;
        recordingTransportGeneration++;
        if(voiceRecording||voicePreparing)throw new IOException("Finish active voice question first");
        if(standbyEnabled||voiceArmed)stopStandby();
        if(result.optBoolean("spp_auth_success_callback")){startRecordingTask();return;}
        recordingPreparing=true;
        result.put("recording",new JSONObject().put("phase","connecting").put("bytes",0).put("audio_uploaded",false));stage("recording_preparing",true);
        recordingTransportOwned=!sppRequested;sppRequested=true;
        boolean submitted=(Boolean)runtime.type("I3.L").getMethod("f",runtime.type("I3.D")).invoke(null,targetDevice);
        result.put("spp_connect_submitted",submitted);stage("recording_spp_requested",submitted);
        if(!submitted){failRecordingPreparation();return;}
        final String command=activeCommand;final long deadline=SystemClock.elapsedRealtime()+20000;
        handler.post(new Runnable(){public void run(){if(finished||finishing||!command.equals(activeCommand))return;try{
            if(result.optBoolean("spp_auth_success_callback")){startRecordingTask();return;}
            if(SystemClock.elapsedRealtime()>deadline){failRecordingPreparation();return;}
            handler.postDelayed(this,250);
        }catch(Exception e){try{result.getJSONObject("last_command").put("status","failed");stage("recording_start_failed",e.getClass().getSimpleName());}catch(Exception ignored){}}}});
    }
    private void failRecordingPreparation()throws Exception{
        recordingPreparing=false;
        result.put("recording",new JSONObject().put("phase","failed").put("error","录音连接未准备好，请重试。").put("audio_uploaded",false));
        result.getJSONObject("last_command").put("status","failed");stage("recording_transport_failed",true);releaseRecordingTransport();
    }
    private void releaseRecordingTransport(){
        if(!recordingTransportOwned)return;
        try{runtime.type("I3.L").getMethod("i",String.class,runtime.type("S3.B")).invoke(null,(String)field(targetDevice,"a"),runtime.type("S3.B").getField("c").get(null));sppRequested=false;recordingTransportOwned=false;}catch(Exception ignored){}
    }
    private void startRecordingTask() throws Exception {
        recordingPreparing=false;
        if(recorder!=null&&recorder.busy())throw new IOException("Recording already active");
        if(voiceRecording||voicePreparing)throw new IOException("Please finish the active voice question first");
        if(standbyEnabled||voiceArmed)stopStandby();
        if(recorder!=null)recorder.close();
        final GlassesRecorder[] owner=new GlassesRecorder[1];
        owner[0]=new GlassesRecorder(this,handler,new GlassesRecorder.Host(){
            public void send(int type,JSONObject body,String id)throws Exception{sendBusiness("RECORDING_SERVICE",type,body,id);}
            public void changed(JSONObject state){if(recorder!=owner[0])return;try{
                result.put("recording",state);
                String phase=state.optString("phase");
                if(phase.equals("saved")||phase.equals("failed"))nextStandbyAttempt=SystemClock.elapsedRealtime()+3000;
                if(!phase.equals(recordingNotificationPhase)){recordingNotificationPhase=phase;ConnectionService.recordingState(SdkProbeActivity.this,phase);}
                if(("record-start".equals(activeCommandKind)&&("recording".equals(phase)||"failed".equals(phase)))
                    ||("record-stop".equals(activeCommandKind)&&("saved".equals(phase)||"failed".equals(phase))))
                    result.getJSONObject("last_command").put("status","failed".equals(phase)?"failed":"completed");
                stage("recording_state",new JSONObject().put("phase",phase).put("bytes",state.optInt("bytes")));
                if((phase.equals("saved")||phase.equals("failed"))&&recordingTransportOwned){
                    final long generation=recordingTransportGeneration;
                    handler.postDelayed(()->{if(finished||finishing||generation!=recordingTransportGeneration||recorder!=owner[0]||recorder.busy())return;
                        try{runtime.type("I3.L").getMethod("i",String.class,runtime.type("S3.B")).invoke(null,(String)field(targetDevice,"a"),runtime.type("S3.B").getField("c").get(null));sppRequested=false;recordingTransportOwned=false;}catch(Exception ignored){}},1500);}
            }catch(Exception ignored){}}
        });recorder=owner[0];recorder.start();
    }
    private long lastPartialAt;
    private String lastPartialText = "";
    private final Map<String, Runnable> sendContinuations = new HashMap<>();
    private boolean sppRequested;
    private String activeCommand, activeCommandKind;
    private final Set<String> consumedCommands = new HashSet<>();
    private long lastHeartbeat;
    private final Runnable authDeadline = () -> complete("failed", "No AUTH_SUCCESS callback within 45 seconds");
    private boolean connectMode, connectAttempted, finishing, discoveryStarted;
    private volatile boolean finished;
    private long startedAt;

    private void stage(String name, Object value) throws Exception {
        if (finished || !sessions.owns(this)) return;
        result.put("connection_service_running", ConnectionService.running);
        stages.put(new JSONObject().put("stage", name).put("elapsed_ms", SystemClock.elapsedRealtime() - startedAt).put("value", value));
        if (stages.length() > 120) stages.remove(0);
        result.put("stages", stages);
        result.put("sampled_at_ms", System.currentTimeMillis());
        File tmp = new File(getFilesDir(), "result.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(result.toString(2).getBytes("UTF-8")); out.getFD().sync();
        }
        if (!tmp.renameTo(new File(getFilesDir(), "result.json"))) throw new IOException("result rename");
    }

    private Object field(Object owner, String name) throws Exception { return owner.getClass().getField(name).get(owner); }

    private boolean systemConnected() {
        for (BluetoothDevice device : manager.getConnectedDevices(BluetoothProfile.GATT))
            if (address.equalsIgnoreCase(device.getAddress())) return true;
        return false;
    }

    private boolean matches(Object device) throws Exception {
        Object peripheral = field(device, "b");
        Object bluetooth = field(peripheral, "J");
        if (bluetooth instanceof BluetoothDevice && address.equalsIgnoreCase(((BluetoothDevice) bluetooth).getAddress())) return true;
        for (String key : new String[]{"f", "n", "o"})
            if (address.equalsIgnoreCase(String.valueOf(field(peripheral, key)))) return true;
        return false;
    }

    private Object findTarget() throws Exception {
        Map<?, ?> devices = (Map<?, ?>) runtime.type("I3.s").getField("b").get(null);
        for (Object device : new ArrayList<Object>(devices.values())) if (matches(device)) return device;
        Map<?, ?> cores = (Map<?, ?>) runtime.type("I3.A").getField("c").get(null);
        for (Object core : new ArrayList<Object>(cores.values())) {
            Object peripheral = field(core, "a");
            if (peripheral == null) continue;
            Object bluetooth = field(peripheral, "J");
            if (bluetooth instanceof BluetoothDevice && address.equalsIgnoreCase(((BluetoothDevice) bluetooth).getAddress())) {
                return runtime.type("I3.L").getMethod("g", runtime.type("I3.y")).invoke(null, core);
            }
        }
        return null;
    }

    private void stopDiscovery() throws Exception {
        if (!discoveryStarted) return;
        runtime.type("I3.L").getMethod("y").invoke(null);
        // The manager may already report stopped while a scanner callback is pending.
        Class<?> scan = runtime.type("V3.G");
        if (scan.getField("d").getBoolean(null)) scan.getMethod("h").invoke(null);
        boolean stopped = !scan.getField("d").getBoolean(null);
        result.put("sdk_scan_stopped", stopped);
        if (!stopped) throw new IllegalStateException("SDK scan did not stop");
        discoveryStarted = false;
    }

    private void complete(String status, String reason) {
        if (finished || finishing) return;
        // Rejected/late instances own no shared state, notification sink or service.
        if (!sessions.owns(this)) {
            finished = true;
            handler.removeCallbacksAndMessages(null);
            voiceWorker.shutdownNow();
            return;
        }
        finishing = true;
        boolean clean = true;
        try {
            clean &= cleanupStep(() -> PhoneNotifications.detach(phoneNotificationSink));
            clean &= cleanupStep(() -> { if (recorder != null) recorder.close(); });
            if (voiceRecording) {
                clean &= cleanupStep(() -> sendBusiness("VOICE_ASSISTANT", 2,
                    new JSONObject().put("rc", 2), "voice-stop-" + voiceCommand));
                voiceRecording = false; voiceArmed = false;
            }
            if (nativeReply && targetDevice != null && voiceCommand != null)
                clean &= cleanupStep(() -> sendBusiness("VOICE_ASSISTANT", 7,
                    new JSONObject().put("rc", 1), "voice-exit-" + voiceCommand));
            clean &= cleanupStep(() -> {
                if (connectionServiceStarted) stopService(new android.content.Intent(this, ConnectionService.class));
                connectionServiceStarted = false;
            });
            standbyEnabled = false; standbyReady = false;
            sendContinuations.clear();
            clean &= cleanupStep(() -> { if (cloudCancellation != null) cloudCancellation.cancel(); });
            clean &= cleanupStep(this::closeStreaming);
            cloudVoice = false; voicePreparing = false;
            OpusAudio.clear(voiceAudio); voiceWorker.shutdownNow();
            handler.removeCallbacksAndMessages(null);
            // Independent cleanup steps: a failed scanner stop must not skip BLE disconnect.
            clean &= cleanupStep(this::stopDiscovery);
            if (connectAttempted && targetDevice != null) {
                if (sppRequested) clean &= cleanupStep(() -> {
                    Object spp = runtime.type("S3.B").getField("c").get(null);
                    runtime.type("I3.L").getMethod("i", String.class, runtime.type("S3.B"))
                        .invoke(null, (String)field(targetDevice, "a"), spp);
                    result.put("sdk_spp_disconnect_requested", true);
                });
                clean &= cleanupStep(() -> {
                    // Disconnect only; never invoke unbind/cache removal.
                    Object ble = runtime.type("S3.B").getField("b").get(null);
                    runtime.type("I3.L").getMethod("i", String.class, runtime.type("S3.B"))
                        .invoke(null, (String)field(targetDevice, "a"), ble);
                    result.put("sdk_disconnect_requested", true);
                });
            }
            clean &= cleanupStep(() -> { if (channelObserver != null) { channelObserver.close(); result.put("channel_observer_removed", true); } });
            clean &= cleanupStep(() -> { if (routeObserver != null) { routeObserver.close(); result.put("route_observer_removed", true); } });
            clean &= cleanupStep(() -> {
                if (listeners != null && listener != null) {
                    listeners.remove(listener);
                    result.put("listener_removed", !listeners.contains(listener));
                }
            });
            clean &= cleanupStep(() -> { if (messageBridge != null) { messageBridge.close(); result.put("message_listener_removed", true); } });
            result.put("status", clean ? status : "failed").put("connect_attempted", connectAttempted);
            if (!clean) result.put("reason", "One or more cleanup steps failed; see local diagnostic log");
            else if (reason != null) result.put("reason", reason);
            result.put("own_cache_key_count_at_end", getSharedPreferences("rayneo_net_bonded_devices", MODE_PRIVATE).getAll().size());
            stage("finished", true);
            display.setText(result.toString(2));
        } catch (Throwable e) {
            android.util.Log.e("RayNeoSdkProbe", "final result", e);
        } finally {
            // Cleanup failure must not leave a lease that blocks every later connection.
            handler.removeCallbacksAndMessages(null);
            cleanupStep(() -> { if (cloudCancellation != null) cloudCancellation.cancel(); });
            cleanupStep(this::closeStreaming);
            cleanupStep(() -> voiceWorker.shutdownNow());
            if (sessions.owns(this)) {
                cleanupStep(() -> {
                    if (connectionServiceStarted) stopService(new android.content.Intent(this, ConnectionService.class));
                });
                connectionServiceStarted = false;
                finished = true;
                connectionActive = false;
                sessions.release(this);
            }
        }
    }

    private interface CleanupAction { void run() throws Exception; }
    private boolean cleanupStep(CleanupAction action) {
        try { action.run(); return true; }
        catch (Throwable e) { android.util.Log.e("RayNeoSdkProbe", "cleanup step", e); return false; }
    }

    private static String rootError(Throwable value) {
        while (value instanceof InvocationTargetException && value.getCause() != null) value = value.getCause();
        return value.getClass().getName() + ": " + value.getMessage();
    }

    private void onEvent(String method, Object[] args) {
        if (finished || finishing || args == null || args.length < 2) return;
        try {
            if (!matches(args[0])) return;
            JSONObject event = new JSONObject().put("method", method);
            for (int i = 1; i < args.length; i++) {
                Object value = args[i];
                event.put("arg" + i, value instanceof Enum ? ((Enum<?>) value).name() : JSONObject.NULL);
            }
            stage("sdk_callback", event);
            if ("d".equals(method) && args[1] instanceof Enum) {
                String spp = ((Enum<?>)args[1]).name();
                result.put("last_spp_state", spp);
                if (spp.equals("AUTH_SUCCESS") && args.length > 2 && args[2] == null) {
                    result.put("spp_auth_success_callback", true);
                    if ("spp".equals(activeCommandKind)) result.getJSONObject("last_command").put("status", "completed");
                } else if (spp.equals("DISCONNECT")) result.put("spp_auth_success_callback", false);
            }
            if ("a".equals(method) && args[1] instanceof Enum) {
                String bond = ((Enum<?>)args[1]).name();
                result.put("last_bond_callback", bond);
                if (pairingRequested && bond.equals("BONDED") && args.length > 2 && args[2] == null
                        && manager.getAdapter().getRemoteDevice(address).getBondState() == BluetoothDevice.BOND_BONDED) {
                    // Same persistence step as the original plugin's j.e bond callback.
                    runtime.type("I3.l").getMethod("a", runtime.type("I3.e0")).invoke(null, field(args[0], "b"));
                    result.put("sdk_bond_success", true);
                    refreshBondState();
                    if ("pair".equals(activeCommandKind)) result.getJSONObject("last_command").put("status", "completed");
                    stage("system_pairing_completed", true);
                } else if (pairingRequested && bond.equals("BONDED_FAIL") && "pair".equals(activeCommandKind)) {
                    result.getJSONObject("last_command").put("status", "failed"); stage("system_pairing_failed", true);
                }
            }
            if ("e".equals(method) && args[1] instanceof Enum) {
                String state = ((Enum<?>) args[1]).name();
                result.put("last_ble_state", state);
                if (connectAttempted && "CONNECTED".equals(state)) result.put("ble_connected_callback", true);
                if (connectAttempted && "AUTH_SUCCESS".equals(state)) {
                    result.put("auth_success_callback", true);
                    handler.removeCallbacks(authDeadline);
                    if (!authenticated) {
                        authenticated = true;
                        if (businessProbe == null) complete("sdk_auth_passed", null);
                        else beginBusinessProbe();
                    }
                } else if (connectAttempted && (state.contains("FAIL") || state.contains("TIMEOUT") || state.contains("INTERRUPT"))) {
                    complete("failed", "SDK BLE state: " + state);
                } else if (authenticated && state.equals("DISCONNECTED")) {
                    complete("failed", "Disconnected during business observation");
                }
            }
        } catch (Throwable e) { complete("failed", rootError(e)); }
    }

    private void tick() {
        if (finished || finishing) return;
        try {
            Object found = findTarget();
            if (found != null && targetDevice == null) {
                targetDevice = found;
                result.put("target_discovered", true);
                Object per = field(found, "b");
                stage("sdk_target_found", new JSONObject().put("name", found.getClass().getMethod("a").invoke(found))
                    .put("model", String.valueOf(per.getClass().getMethod("f").invoke(per)))
                    .put("ble_state", ((Enum<?>) field(per, "L")).name()));
            }
            if (connectMode && targetDevice != null && !connectAttempted) {
                BluetoothDevice bluetooth = (BluetoothDevice) field(field(targetDevice, "b"), "J");
                if (systemConnected() || bluetooth == null || bluetooth.getBondState() != (reconnectMode ? BluetoothDevice.BOND_BONDED : BluetoothDevice.BOND_NONE)) {
                    complete("blocked_or_failed", "Existing connection or pairing detected before SDK connect"); return;
                }
                if (!"".equals(field(targetDevice, "g")) || !"".equals(field(targetDevice, "h")))
                    throw new IllegalStateException("Expected fresh guest device with empty account and bond key");
                // Original facade h.b(id, type, isPair) sets D.j before connecting.
                // A freshly reset device needs first pairing, not the default reconnect.
                runtime.type("I3.D").getField("j").setBoolean(targetDevice, !reconnectMode);
                stage(reconnectMode ? "sdk_saved_device_reconnect" : "sdk_first_pairing", true);
                stopDiscovery();
                connectAttempted = true;
                stage("sdk_connect_enter", "I3.L.w, foreground BLE, guest account, one attempt");
                runtime.type("I3.L").getMethod("w", runtime.type("I3.D")).invoke(null, targetDevice);
                stage("sdk_connect_returned", true);
                handler.postDelayed(authDeadline, 45000);
            }
            handler.postDelayed(this::tick, 300);
        } catch (Throwable e) { complete("failed", rootError(e)); }
    }

    private void beginBusinessProbe() throws Exception {
        stage("business_observation_started", true);
        handler.postDelayed(() -> {
            if ("session".equals(businessProbe) && result.optString("status").equals("sdk_session_ready")) return;
            try {
                JSONObject payload = new JSONObject().put("data", "").put("mode", 0).put("value", 0);
                statusQuerySent = true;
                sendBusiness("LAUNCHER", 1, new JSONObject().put("cmd", "request_general_status").put("payload", payload), "general-status");
            } catch (Throwable e) { complete("failed", rootError(e)); }
        }, 500);
        handler.postDelayed(() -> {
            if ("session".equals(businessProbe) && result.optString("status").equals("sdk_session_ready")) return;
            boolean statusOK = result.optBoolean("status_reply_received");
            boolean textMode = "text".equals(businessProbe);
            boolean ok = statusOK && (!textMode || result.optBoolean("text_send_completed"));
            complete(ok ? textMode ? "sdk_text_sent" : "sdk_status_passed" : "failed",
                ok ? null : !statusOK ? "No matching general status response" : "No successful text send callback");
        }, "text".equals(businessProbe) ? 30000 : 20000);
    }

    private void sessionTick() {
        if (finished || finishing) return;
        try {
            File commandFile = new File(getFilesDir(), "session-command.json");
            if (commandFile.isFile()) {
                if (commandFile.length() > 8192) throw new IllegalArgumentException("Session command too large");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (FileInputStream input = new FileInputStream(commandFile)) {
                    byte[] buf = new byte[1024]; int n;
                    while ((n = input.read(buf)) != -1) { bytes.write(buf, 0, n); if (bytes.size() > 8192) throw new IllegalArgumentException("Session command too large"); }
                }
                if (!commandFile.delete()) throw new IOException("Cannot consume session command");
                JSONObject command = new JSONObject(bytes.toString("UTF-8"));
                String id = command.getString("command_id");
                if (!result.getString("session_id").equals(command.optString("session_id")) || consumedCommands.contains(id)) {
                    stage("command_rejected", "Stale session or repeated command");
                } else if (!persistentSession && consumedCommands.size() >= 128) {
                    complete("sdk_session_completed", "Session command limit reached"); return;
                } else if (result.optJSONObject("last_command") != null
                        && "pending".equals(result.getJSONObject("last_command").optString("status"))
                        && !"stop".equals(command.optString("kind")) && !"standby-off".equals(command.optString("kind")) && !"record-stop".equals(command.optString("kind"))) {
                    stage("command_rejected", "Previous command still pending");
                } else if (standbyEnabled && !Arrays.asList("status", "stop", "standby-off", "record-start", "notify").contains(command.optString("kind"))) {
                    stage("command_rejected", "Stop standby before another test");
                } else if (recorder!=null&&recorder.busy()&&!Arrays.asList("record-stop","status","stop").contains(command.optString("kind"))) {
                    stage("command_rejected","Recording owns the microphone");
                } else {
                    if(consumedCommands.size()>=128)consumedCommands.clear();
                    consumedCommands.add(id);
                    activeCommand = id; activeCommandKind = command.getString("kind");
                    result.put("last_command", new JSONObject().put("id", id).put("kind", activeCommandKind).put("status", "pending"));
                    stage("session_command", activeCommandKind);
                    if (activeCommandKind.equals("record-start")) {
                        prepareRecordingTask();
                    } else if(activeCommandKind.equals("record-stop")) {
                        if(recordingPreparing){recordingPreparing=false;recordingTransportGeneration++;releaseRecordingTransport();nextStandbyAttempt=SystemClock.elapsedRealtime()+3000;
                            result.put("recording",new JSONObject().put("phase","cancelled").put("audio_uploaded",false));result.getJSONObject("last_command").put("status","completed");stage("record_start_cancelled",true);}
                        else if(recorder==null||!recorder.busy()){result.getJSONObject("last_command").put("status","failed");stage("record_stop_no_task",true);}
                        else recorder.stop();
                    } else if (activeCommandKind.equals("stop")) {
                        result.getJSONObject("last_command").put("status", "completed");
                        complete("sdk_session_completed", "Stopped by user"); return;
                    } else if (activeCommandKind.equals("standby-off")) {
                        assistantPrefs().edit().putBoolean("auto_standby",false).apply();
                        stopStandby();
                        result.getJSONObject("last_command").put("status", "completed"); stage("standby_disabled", true);
                    } else if (activeCommandKind.equals("spp-off")) {
                        if (voiceRecording || voicePreparing || voiceArmed) throw new IllegalStateException("Stop voice test first");
                        runtime.type("I3.L").getMethod("i", String.class, runtime.type("S3.B"))
                            .invoke(null, (String)field(targetDevice, "a"), runtime.type("S3.B").getField("c").get(null));
                        final String closing = id;
                        handler.postDelayed(() -> { try {
                            if (!finished && closing.equals(activeCommand)) {
                                boolean off = !result.optBoolean("spp_auth_success_callback");
                                result.getJSONObject("last_command").put("status", off ? "completed" : "failed"); stage("spp_idle_disconnect", off);
                            }
                        } catch (Exception e) { complete("failed", rootError(e)); } }, 2000);
                    } else if (activeCommandKind.equals("spp")) {
                        refreshBondState();
                        if (result.optInt("system_bond_state") != 12 || result.optInt("own_saved_target_count") != 1)
                            throw new IllegalStateException("SPP requires saved system pairing");
                        if (sppRequested) throw new IllegalStateException("SPP already requested in this session");
                        sppRequested = true;
                        boolean submitted = (Boolean)runtime.type("I3.L").getMethod("f", runtime.type("I3.D")).invoke(null, targetDevice);
                        result.put("spp_connect_submitted", submitted);
                        stage("spp_connect_requested", submitted);
                        if (!submitted) result.getJSONObject("last_command").put("status", "failed");
                        final String sppId = id;
                        handler.postDelayed(() -> {
                            try {
                                if (!finished && sppId.equals(activeCommand) && "pending".equals(result.getJSONObject("last_command").optString("status"))) {
                                    result.getJSONObject("last_command").put("status", "failed"); stage("spp_auth_timeout", true);
                                }
                            } catch (Exception e) { complete("failed", rootError(e)); }
                        }, 25000);
                    } else if (activeCommandKind.equals("wake")) {
                        result.put("voice_wakeup_request", new JSONObject().put("enabled", true).put("send_completed", false));
                        JSONObject payload = new JSONObject().put("data", "").put("mode", 1).put("value", 0);
                        sendBusiness("LAUNCHER", 16, new JSONObject().put("cmd", "set_ai_voice_wakeup").put("payload", payload), id);
                    } else if (activeCommandKind.equals("codec")) {
                        final String codecCommand = id;
                        voiceWorker.submit(() -> {
                            JSONObject check = new JSONObject();
                            List<byte[]> synthetic = new ArrayList<>();
                            try {
                                for (int i = 0; i < 50; i++) synthetic.add(new byte[]{(byte)0xf8, (byte)0xff, (byte)0xfe});
                                OpusAudio.Decoded decoded = OpusAudio.decode(synthetic);
                                check.put("passed", decoded.samples == 48000).put("sample_rate", decoded.rate).put("samples", decoded.samples)
                                    .put("codec", decoded.codec).put("synthetic", true).put("audio_uploaded", false);
                                Arrays.fill(decoded.wav, (byte)0);
                            } catch (Exception e) { try { check.put("passed", false).put("error", e.getClass().getSimpleName() + ": " + e.getMessage()); } catch (Exception ignored) {} }
                            finally { OpusAudio.clear(synthetic); }
                            handler.post(() -> { try {
                                if (finished || finishing || !codecCommand.equals(activeCommand)) return;
                                result.put("codec_test", check); result.getJSONObject("last_command").put("status", check.optBoolean("passed") ? "completed" : "failed");
                                stage("codec_test_finished", check);
                            } catch (Exception e) { complete("failed", rootError(e)); } });
                        });
                    } else if (Arrays.asList("voice", "audio", "voice-cloud", "voice-standby", "voice-ble", "voice-native").contains(activeCommandKind)) {
                        if(activeCommandKind.equals("voice-standby")){
                            assistantPrefs().edit().putBoolean("auto_standby",true).apply();beginStandby(id);
                        }else{
                        assistantPrefs().edit().putBoolean("auto_standby",false).apply();
                        if (activeCommandKind.equals("audio") && !result.optBoolean("spp_auth_success_callback"))
                            throw new IllegalStateException("Direct audio diagnostic requires authenticated SPP");
                        cloudVoice = activeCommandKind.equals("voice-cloud") || activeCommandKind.equals("voice-ble") || activeCommandKind.equals("voice-native"); OpusAudio.clear(voiceAudio);
                        nativeReply = activeCommandKind.equals("voice-native");
                        if (cloudVoice) {
                            JSONObject cloudConfig = CloudConfig.load(this);
                            if (cloudConfig.optString("dashscope_key").isEmpty() || cloudConfig.optString("deepseek_key").isEmpty())
                                throw new IllegalStateException("Please configure both provider keys first");
                        }
                        voiceCommand = id; voiceArmed = !cloudVoice; voiceRecording = false;
                        voicePackets = 0; voiceBytes = 0; voiceAfterStop = 0;
                        result.put("voice_test", new JSONObject().put("phase", "awaiting_wakeup").put("audio_saved", false).put("audio_uploaded", false)
                            .put("cloud_enabled", cloudVoice).put("audio_source", "glasses")
                            .put("trigger", activeCommandKind.equals("audio") ? "manual_diagnostic" : "glasses_wakeup"));
                        if (cloudVoice) prepareGlassesVoice(id);
                        else if (activeCommandKind.equals("audio")) startVoiceCapture();
                        else {
                            stage("voice_waiting_for_wakeup", true);
                            final String armedId = id;
                            handler.postDelayed(() -> { if (voiceArmed && !voiceRecording && armedId.equals(voiceCommand)) finishVoice("No wakeup within 90 seconds"); }, 90000);
                        }
                        }
                    } else if (activeCommandKind.equals("pair")) {
                        if (pairingRequested) throw new IllegalStateException("Pairing already requested in this session");
                        pairingRequested = true;
                        stage("system_pairing_requested", "Original SDK I3.L.h");
                        runtime.type("I3.L").getMethod("h", runtime.type("I3.D")).invoke(null, targetDevice);
                        final String pairingId = id;
                        handler.postDelayed(() -> {
                            try {
                                if (!finished && pairingId.equals(activeCommand) && "pending".equals(result.getJSONObject("last_command").optString("status"))) {
                                    result.getJSONObject("last_command").put("status", "failed"); stage("system_pairing_timeout", true);
                                }
                            } catch (Exception e) { complete("failed", rootError(e)); }
                        }, 65000);
                    } else if (activeCommandKind.equals("notify")) {
                        customNotification = command.getJSONObject("notification");
                        validateNotification(customNotification);
                        textAttempted = false;
                        result.put("text_send_completed", false);
                        result.remove("notification_reply");
                        sendTestText();
                    } else if (activeCommandKind.equals("status")) {
                        JSONObject payload = new JSONObject().put("data", "").put("mode", 0).put("value", 0);
                        sendBusiness("LAUNCHER", 1, new JSONObject().put("cmd", "request_general_status").put("payload", payload), id);
                    } else throw new IllegalArgumentException("Unknown session command");
                }
            }
            if (SystemClock.elapsedRealtime() - lastHeartbeat >= (standbyReady ? 30000 : 5000)) {
                lastHeartbeat = SystemClock.elapsedRealtime(); refreshBondState(); stage("session_heartbeat", true);
            }
            restoreStandbyIfNeeded();
            PhoneNotifications.tick(this);
            handler.postDelayed(this::sessionTick, standbyReady ? 1000 : 300);
        } catch (Throwable e) { complete("failed", rootError(e)); }
    }

    private void voiceEvent(BusinessEnvelope wire) throws Exception {
        // Hardware emits type8 (close old answer) then type1 ~20ms later on a fresh wake.
        // Idle owns no recording to finish: retain the armed listener and invalidate old page exit.
        if (wire.type == 8 && standbyReady && !voiceRecording) {
            nativeDisplayUntil = 0;
            activeCommand = null;
            stage("standby_old_page_exited", true); return;
        }
        if (wire.type == 8 && nativeReply && !standbyReady && !voicePreparing
                && result.optJSONObject("last_command") != null && "pending".equals(result.getJSONObject("last_command").optString("status"))) {
            if (cloudCancellation != null) cloudCancellation.cancel(); closeStreaming(); sendContinuations.clear();
            voiceArmed = false; voiceRecording = false; cloudVoice = false; OpusAudio.clear(voiceAudio);
            voiceStopAt = SystemClock.elapsedRealtime(); voiceCommand = null; activeCommand = null;
            result.getJSONObject("last_command").put("status", "cancelled");
            result.getJSONObject("voice_test").put("phase", "cancelled").put("reason", "Glasses exited native assistant");
            stage("native_round_cancelled_by_glasses", true); rearmStandby(); return;
        }
        if (wire.type == 3) {
            result.put("audio_receive_packets_total", result.optLong("audio_receive_packets_total") + 1);
            if (!voiceRecording && SystemClock.elapsedRealtime() - voiceStopAt > 3000)
                result.put("idle_audio_packets", result.optLong("idle_audio_packets") + 1);
        }
        if (wire.type != 3) stage("voice_event", new JSONObject().put("type", wire.type).put("data_bytes", wire.dataBytes));
        if (wire.type == 3 && !voiceRecording && voiceCommand != null) voiceAfterStop++;
        if (!voiceArmed) return;
        if (VoiceWakePolicy.accepts(wire.type, voiceArmed, voiceRecording,
                standbyEnabled && standbyReady && nativeReply && SystemClock.elapsedRealtime() < nativeDisplayUntil)) {
            nativeDisplayUntil = 0;
            if (standbyEnabled && standbyReady) {
                standbyReady = false; standbyRounds++;
                activeCommand = voiceCommand; activeCommandKind = "voice-cloud";
                result.put("last_command", new JSONObject().put("id", voiceCommand).put("kind", "voice-cloud").put("status", "pending"));
                publishStandby("recording");
            }
            startVoiceCapture();
        } else if (wire.type == 3 && voiceRecording) {
            if (cloudVoice) {
                if (wire.audio == null) {
                    // A metadata-only frame may already be queued before this wake starts capture.
                    // It is not owned by the new round; wait for newly captured packets instead.
                    result.put("stale_audio_metadata_ignored", result.optLong("stale_audio_metadata_ignored") + 1);
                    return;
                }
                if (wire.audio.length == 0 || voiceAudio.size() >= 512 || voiceBytes + wire.dataBytes > 1048576) {
                    finishVoice("Audio capture limit or missing payload"); return;
                }
                if (streamingAsr != null) {
                    if (!streamingAsr.offer(wire.audio)) { failStreaming(voiceCommand, "实时音频缓存已满", streamingAsr.uploadStarted()); return; }
                } else voiceAudio.add(wire.audio.clone());
            }
            voicePackets++; voiceBytes += wire.dataBytes;
            result.getJSONObject("voice_test").put("packets", voicePackets).put("bytes", voiceBytes);
            if (voicePackets == 1) stage("voice_first_audio", wire.dataBytes);
            if (voiceBytes > 1048576) finishVoice("Audio byte limit reached");
        } else if (wire.type == 8) finishVoice("Glasses exited voice mode");
    }

    private void startVoiceCapture() throws Exception {
        voiceRecording = true;
        if (cloudVoice && nativeReply) {
            JSONObject config = CloudConfig.load(this);
            if (config.optBoolean("streaming_asr")) startStreaming(config, voiceCommand);
        }
        result.getJSONObject("voice_test").put("phase", "recording");
        sendBusiness("VOICE_ASSISTANT", 2, new JSONObject().put("rc", 1), "voice-start-" + voiceCommand);
        stage("voice_recording_requested", result.getJSONObject("voice_test").getString("trigger"));
        final String started = voiceCommand;
        if (streamingAsr == null)
            handler.postDelayed(() -> { if (voiceArmed && started.equals(voiceCommand)) finishVoice("Eight second capture complete"); }, 8000);
    }

    private void finishVoice(String reason) {
        if (!voiceArmed || finished || finishing) return;
        if (streamingAsr != null) { failStreaming(voiceCommand, reason, streamingAsr.uploadStarted()); return; }
        voiceArmed = false;
        boolean wasRecording = voiceRecording; voiceRecording = false; voiceAfterStop = 0;
        voiceStopAt = SystemClock.elapsedRealtime();
        final String ending = voiceCommand;
        try {
            result.getJSONObject("voice_test").put("phase", "stopping").put("reason", reason);
            if (wasRecording) sendBusiness("VOICE_ASSISTANT", 2, new JSONObject().put("rc", 2), "voice-stop-" + ending);
            if (wasRecording && !nativeReply) sendBusiness("VOICE_ASSISTANT", 7, new JSONObject().put("rc", 1), "voice-exit-" + ending);
            stage("voice_stopping", true);
            handler.postDelayed(() -> {
                try {
                    if (finished || !ending.equals(voiceCommand)) return;
                    JSONObject voice = result.getJSONObject("voice_test");
                    voice.put("phase", "finished").put("packets", voicePackets).put("bytes", voiceBytes).put("packets_after_stop", voiceAfterStop);
                    boolean ok = voicePackets > 0 && voiceBytes > 0 && voice.optBoolean("stop_send_completed");
                    voice.put("transport_test_passed", ok);
                    if (cloudVoice && ok && (reason.equals("Eight second capture complete") || reason.equals("Glasses exited voice mode"))) {
                        runGlassesCloud(ending);
                    } else {
                        if (ending.equals(activeCommand)) result.getJSONObject("last_command").put("status", ok && !cloudVoice ? "completed" : "failed");
                        OpusAudio.clear(voiceAudio); cloudVoice = false;
                    }
                    stage("voice_test_finished", ok);
                    if (!cloudVoice && standbyEnabled && !ok) rearmStandby();
                } catch (Exception e) { complete("failed", rootError(e)); }
            }, 3000);
        } catch (Exception e) { complete("failed", rootError(e)); }
    }

    private void failVoicePreparation(String id, String reason) {
        if (finished || finishing || !id.equals(activeCommand)) return;
        voicePreparing = false; voiceArmed = false; cloudVoice = false; standbyEnabled = false; standbyReady = false; OpusAudio.clear(voiceAudio);
        try {
            result.getJSONObject("voice_test").put("phase", "failed").put("reason", reason);
            result.getJSONObject("last_command").put("status", "failed"); publishStandby("failed"); stage("voice_prepare_failed", reason);
        } catch (Exception e) { complete("failed", rootError(e)); }
    }

    private void prepareGlassesVoice(String id) {
        try {
            voicePreparing = true; result.getJSONObject("voice_test").put("phase", "preparing");
            refreshBondState();
            if (result.optInt("system_bond_state") != 12 || result.optInt("own_saved_target_count") != 1) {
                failVoicePreparation(id, "请先完成首次系统配对；未重新配对或清空设备"); return;
            }
            // BLE-only voice control and audio have passed the real-device round trip.
            result.getJSONObject("voice_test").put("phase", "enabling_wakeup").put("transport_policy", "BLE");
            JSONObject payload = new JSONObject().put("data", "").put("mode", 1).put("value", 0);
            sendBusiness("LAUNCHER", 16, new JSONObject().put("cmd", "set_ai_voice_wakeup").put("payload", payload), "voice-wake-" + id);
            handler.postDelayed(() -> { if (voicePreparing) failVoicePreparation(id, "未收到开启唤醒的发送回调"); }, 12000);
        } catch (Exception e) { failVoicePreparation(id, "语音准备失败：" + e.getClass().getSimpleName()); }
    }

    private void publishStandby(String phase) throws Exception {
        result.put("standby", new JSONObject().put("enabled", standbyEnabled).put("ready", standbyReady)
            .put("control_id", standbyControl == null ? JSONObject.NULL : standbyControl).put("rounds", standbyRounds)
            .put("phase", phase).put("idle_audio_requested", false).put("scope", persistentSession?"persistent_connection":"foreground_session")
            .put("auto_restore",assistantPrefs().getBoolean("auto_standby",true)));
    }
    private void rearmStandby() throws Exception {
        if (!standbyEnabled || finished || finishing) return;
        if (!persistentSession && standbyRounds >= 10) { stopStandby(); stage("standby_round_limit", 10); return; }
        // Idle is only an event subscription: no recorder command, no cloud call, no decoder.
        voiceCommand = UUID.randomUUID().toString(); cloudVoice = true; voiceArmed = true; voiceRecording = false;
        voicePackets = 0; voiceBytes = 0; voiceAfterStop = 0; OpusAudio.clear(voiceAudio);
        standbyReady = true;
        result.put("voice_test", new JSONObject().put("phase", "awaiting_wakeup").put("audio_source", "glasses")
            .put("trigger", "glasses_wakeup").put("cloud_enabled", true).put("audio_saved", false).put("audio_uploaded", false));
        publishStandby("idle"); stage("standby_waiting_for_wakeup", true);
    }
    private void stopStandby() throws Exception {
        standbyEnabled = false; standbyReady = false;
        nativeDisplayUntil = 0;
        sendContinuations.clear();
        if (cloudCancellation != null) cloudCancellation.cancel();
        closeStreaming();
        if (voiceRecording) {
            sendBusiness("VOICE_ASSISTANT", 2, new JSONObject().put("rc", 2), "voice-stop-" + voiceCommand);
        }
        if ((voiceRecording || nativeReply) && voiceCommand != null) {
            sendBusiness("VOICE_ASSISTANT", 7, new JSONObject().put("rc", 1), "voice-exit-" + voiceCommand);
        }
        voiceArmed = false; voiceRecording = false; voicePreparing = false; cloudVoice = false;
        voiceStopAt = SystemClock.elapsedRealtime();
        OpusAudio.clear(voiceAudio);
        if (result.optJSONObject("voice_test") != null) result.getJSONObject("voice_test").put("phase", "cancelled");
        voiceCommand = null; publishStandby("disabled");
    }

    private void writeCloudResult(JSONObject value) throws Exception {
        File temp = new File(getFilesDir(), "glasses-cloud-result.tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(value.toString(2).getBytes("UTF-8")); out.getFD().sync();
        }
        if (!temp.renameTo(new File(getFilesDir(), "cloud-result.json"))) throw new IOException("Cloud result rename");
    }

    private void closeStreaming() {
        StreamingAsr current = streamingAsr; streamingAsr = null;
        if (current != null) current.close();
        try { result.put("asr_stream_active", false); } catch (Exception ignored) {}
    }
    private boolean currentRound(String command) {
        return !finished && !finishing && command.equals(activeCommand) && command.equals(voiceCommand);
    }
    private void stopStreamingCapture(String command) throws Exception {
        if (!currentRound(command)) return;
        if (voiceRecording) {
            voiceRecording = false; voiceArmed = false; voiceStopAt = SystemClock.elapsedRealtime();
            sendBusiness("VOICE_ASSISTANT", 2, new JSONObject().put("rc", 2), "voice-stop-" + command);
            result.getJSONObject("voice_test").put("phase", "finalizing_transcript");
            stage("stream_microphone_stopped", true);
        }
    }
    private void failStreaming(String command, String reason, boolean uploadAttempted) {
        if (!currentRound(command)) return;
        try {
            stopStreamingCapture(command); closeStreaming(); cloudVoice = false;
            sendBusiness("VOICE_ASSISTANT", 7, new JSONObject().put("rc", 1), "voice-exit-" + command);
            JSONObject failure = new JSONObject().put("job_id", command).put("session_id", result.getString("session_id"))
                .put("audio_source", "glasses").put("asr_mode", "streaming").put("audio_saved", false)
                .put("upload_started", uploadAttempted).put("status", "failed").put("error", reason);
            result.put("glasses_cloud", failure); writeCloudResult(failure);
            result.getJSONObject("last_command").put("status", "failed");
            result.getJSONObject("voice_test").put("phase", "failed").put("reason", reason);
            stage("stream_failed", failure); rearmStandby();
        } catch (Exception e) { complete("failed", rootError(e)); }
    }
    private void startStreaming(JSONObject config, String command) throws Exception {
        closeStreaming(); lastPartialAt = 0; lastPartialText = "";
        result.put("cloud_stream_connections_total", result.optLong("cloud_stream_connections_total") + 1).put("asr_stream_active", true);
        result.remove("submitted_transcript"); result.remove("submitted_transcript_final");
        result.getJSONObject("voice_test").put("asr_mode", "streaming");
        streamingAsr = new StreamingAsr(config, new StreamingAsr.Listener() {
            public void event(String name, Object value) { handler.post(() -> { try {
                if (!currentRound(command)) return;
                if (name.equals("stream_upload_started")) {
                    result.put("cloud_upload_requests_total", result.optLong("cloud_upload_requests_total") + 1);
                    result.getJSONObject("voice_test").put("upload_started", true);
                }
                stage(name, value);
            } catch (Exception e) { complete("failed", rootError(e)); } }); }
            public void text(String value, boolean isFinal) { handler.post(() -> { try {
                if (!currentRound(command) || value.isEmpty()) return;
                long now = SystemClock.elapsedRealtime();
                if (!isFinal && (value.equals(lastPartialText) || now - lastPartialAt < 500)) return;
                if (value.getBytes("UTF-8").length > 512) { failStreaming(command, "识别文本超过问题区限制", true); return; }
                lastPartialAt = now; lastPartialText = value;
                result.put("submitted_transcript", value).put("submitted_transcript_final", isFinal);
                sendBusiness("VOICE_ASSISTANT", 5, new JSONObject().put("text", value).put("final", isFinal),
                    "asr-text-" + command + "-" + now);
                stage(isFinal ? "stream_final_text" : "stream_partial_text", value);
            } catch (Exception e) { failStreaming(command, e.getClass().getSimpleName(), true); } }); }
            public void endpoint() { handler.post(() -> { try { stopStreamingCapture(command); }
                catch (Exception e) { complete("failed", rootError(e)); } }); }
            public void failed(String reason, boolean uploaded) { handler.post(() -> failStreaming(command, reason, uploaded)); }
            public void completed(JSONObject asr) { handler.post(() -> { try {
                if (!currentRound(command)) return;
                stopStreamingCapture(command); closeStreaming(); cloudVoice = false;
                runStreamAnswer(config, command, asr);
            } catch (Exception e) { failStreaming(command, e.getClass().getSimpleName(), true); } }); }
        });
        streamingAsr.start();
    }
    private void runStreamAnswer(JSONObject config, String command, JSONObject asr) throws Exception {
        final String session = result.getString("session_id");
        final CloudClient.Cancellation cancel = new CloudClient.Cancellation(); cloudCancellation = cancel;
        result.getJSONObject("voice_test").put("phase", "answering");
        voiceWorker.submit(() -> {
            JSONObject pipeline = new JSONObject();
            try {
                pipeline.put("job_id", command).put("session_id", session).put("audio_source", "glasses")
                    .put("asr_mode", "streaming").put("audio_saved", false).put("audio_uploaded", true).put("lens_verified", false)
                .put("asr", asr).put("sample_rate", asr.getInt("sample_rate")).put("duration_ms", asr.getLong("audio_sent_ms"));
                cancel.check();
                JSONObject answer = CloudClient.ask(config, asr.getString("text"), cancel);
                pipeline.put("answer", answer).put("provider", answer.optString("provider")).put("text", answer.getString("text")).put("status", "completed");
            } catch (Exception e) { try {
                pipeline.put("status", "failed").put("error", e instanceof CloudClient.Failure ? e.getMessage() : e.getClass().getSimpleName());
            } catch (Exception ignored) {} }
            handler.post(() -> { try {
                if (!currentRound(command) || !session.equals(result.optString("session_id"))) return;
                result.put("glasses_cloud", pipeline); writeCloudResult(pipeline);
                if (!"completed".equals(pipeline.optString("status"))) { failStreaming(command, pipeline.optString("error"), true); return; }
                result.getJSONObject("voice_test").put("phase", "sending_answer").put("audio_uploaded", true);
                result.put("text_send_completed", false); stage("glasses_cloud_completed", pipeline);
                sendNativeAnswer(pipeline, command);
            } catch (Exception e) { complete("failed", rootError(e)); } });
        });
    }

    private void runGlassesCloud(String command) throws Exception {
        final List<byte[]> packets = new ArrayList<>(voiceAudio); voiceAudio.clear(); cloudVoice = false;
        final JSONObject config = CloudConfig.load(this);
        final String session = result.getString("session_id");
        final boolean useNativePage = nativeReply;
        final CloudClient.Cancellation cancel = new CloudClient.Cancellation(); cloudCancellation = cancel;
        result.getJSONObject("voice_test").put("phase", "decoding"); stage("glasses_decode_started", packets.size());
        voiceWorker.submit(() -> {
            byte[] wav = null;
            JSONObject pipeline = new JSONObject();
            try {
                OpusAudio.Decoded decoded = OpusAudio.decode(packets); wav = decoded.wav;
                pipeline.put("job_id", command).put("session_id", session).put("audio_source", "glasses")
                    .put("audio_saved", false).put("audio_uploaded", false).put("lens_verified", false)
                    .put("sample_rate", decoded.rate).put("decoded_samples", decoded.samples).put("decoder", decoded.codec)
                    .put("duration_ms", decoded.samples * 1000L / decoded.rate).put("opus_packets", packets.size());
                cancel.check(); if (finished || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                handler.post(() -> { try {
                    if (!finished && !finishing && command.equals(activeCommand)) {
                        result.getJSONObject("voice_test").put("phase", "transcribing").put("upload_started", true);
                        result.put("cloud_upload_requests_total", result.optLong("cloud_upload_requests_total") + 1);
                        stage("glasses_asr_started", true);
                    }
                } catch (Exception e) { complete("failed", rootError(e)); } });
                pipeline.put("upload_started", true);
                JSONObject asr = CloudClient.transcribe(config, wav, "audio/wav", cancel);
                Arrays.fill(wav, (byte)0); wav = null;
                pipeline.put("asr", asr).put("audio_uploaded", true);
                if (useNativePage) {
                    final String transcript = asr.getString("text");
                    if (transcript.getBytes("UTF-8").length > 512) throw new CloudClient.Failure("识别文本超过原生问题区限制");
                    handler.post(() -> { try {
                        if (!finished && !finishing && command.equals(activeCommand)) {
                            cancel.check();
                            result.put("submitted_transcript", transcript);
                            sendBusiness("VOICE_ASSISTANT", 5, new JSONObject().put("text", transcript).put("final", true), "asr-text-" + command);
                        }
                    } catch (Exception e) { if (!(e instanceof InterruptedException)) complete("failed", rootError(e)); } });
                }
                cancel.check(); if (finished || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                JSONObject answer = CloudClient.ask(config, asr.getString("text"), cancel);
                pipeline.put("answer", answer).put("provider", answer.optString("provider")).put("text", answer.getString("text")).put("status", "completed");
            } catch (Exception e) {
                try { pipeline.put("status", "failed").put("error", e instanceof CloudClient.Failure ? e.getMessage() : e.getClass().getSimpleName()); } catch (Exception ignored) {}
            } finally { if (wav != null) Arrays.fill(wav, (byte)0); OpusAudio.clear(packets); }
            handler.post(() -> { try {
                if (finished || finishing || !command.equals(activeCommand) || !session.equals(result.optString("session_id"))) return;
                result.put("glasses_cloud", pipeline); writeCloudResult(pipeline);
                result.getJSONObject("voice_test").put("audio_uploaded", pipeline.optBoolean("audio_uploaded"));
                if (!"completed".equals(pipeline.optString("status"))) {
                    result.getJSONObject("voice_test").put("phase", "failed");
                    result.getJSONObject("last_command").put("status", "failed"); stage("glasses_cloud_failed", pipeline); rearmStandby(); return;
                }
                result.getJSONObject("voice_test").put("phase", "sending_answer");
                customNotification = new JSONObject().put("title", "眼镜语音问答").put("content", pipeline.getJSONObject("answer").optString("lens_text",pipeline.getString("text")));
                validateNotification(customNotification); textAttempted = false; result.put("text_send_completed", false);
                result.remove("notification_reply"); stage("glasses_cloud_completed", pipeline);
                if (useNativePage) sendNativeAnswer(pipeline, command); else sendTestText();
            } catch (Exception e) { complete("failed", rootError(e)); } });
        });
    }

    private void sendNativeAnswer(JSONObject pipeline, String command) throws Exception {
        String text = pipeline.getJSONObject("answer").optString("lens_text",pipeline.getString("text")), query = pipeline.getJSONObject("asr").getString("text");
        List<String> chunks = new ArrayList<>(); StringBuilder chunk = new StringBuilder(); int size = 0;
        for (int point : text.codePoints().toArray()) {
            String value = new String(Character.toChars(point)); int count = value.getBytes("UTF-8").length;
            if (size + count > 480) { chunks.add(chunk.toString()); chunk.setLength(0); size = 0; }
            chunk.append(value); size += count;
        }
        if (chunk.length() > 0) chunks.add(chunk.toString());
        result.put("submitted_text", new JSONObject().put("title", "眼镜原生语音回答").put("content", text).put("uid", command));
        notificationUid = command;
        sendNativeChunk(chunks, 0, query, command);
    }
    private void sendNativeChunk(List<String> chunks, int i, String query, String command) throws Exception {
        if (finished || finishing || !command.equals(activeCommand)) return;
        if (i < chunks.size()) {
            JSONObject answer = new JSONObject().put("sub", "workflow").put("vendor", "deepseek").put("uuid", command).put("sid", command)
                .put("round", -1).put("timestamp", System.currentTimeMillis()).put("query", query).put("domain", "chat").put("intent", "chat")
                .put("payload", new JSONObject()).put("offline", false)
                .put("answer", new JSONObject().put("text", chunks.get(i)).put("isFinal", false));
            String id = "native-answer-" + command + "-" + i;
            sendContinuations.put(id, () -> { try { sendNativeChunk(chunks, i + 1, query, command); } catch (Exception e) { complete("failed", rootError(e)); } });
            sendBusiness("VOICE_ASSISTANT", 32, answer, id);
            return;
        }
        nativeDisplayUntil = SystemClock.elapsedRealtime() + 15000;
        sendBusiness("VOICE_ASSISTANT", 12, new JSONObject(), command);
        handler.postDelayed(() -> { try {
            if (!finished && !finishing && command.equals(activeCommand) && !voiceRecording)
                sendBusiness("VOICE_ASSISTANT", 7, new JSONObject().put("rc", 1), "voice-exit-" + command);
        } catch (Exception e) { complete("failed", rootError(e)); } }, 15000);
    }

    private void refreshBondState() throws Exception {
        result.put("system_bond_state", manager.getAdapter().getRemoteDevice(address).getBondState());
        List<?> saved = (List<?>)runtime.type("I3.l").getMethod("g").invoke(null);
        int count = 0;
        if (saved != null) for (Object per : saved) {
            for (String name : new String[]{"n", "o", "f"}) if (address.equalsIgnoreCase(String.valueOf(field(per, name)))) { count++; break; }
        }
        result.put("own_saved_target_count", count);
    }

    private Object savedTarget() throws Exception {
        List<?> saved = (List<?>)runtime.type("I3.l").getMethod("g").invoke(null);
        Object match = null;
        if (saved != null) for (Object per : saved) {
            boolean matching = false;
            for (String name : new String[]{"n", "o", "f"}) if (address.equalsIgnoreCase(String.valueOf(field(per, name)))) matching = true;
            if (!matching) continue;
            if (match != null) throw new IllegalStateException("Multiple saved target devices");
            runtime.type("I3.e0").getField("J").set(per, manager.getAdapter().getRemoteDevice(address));
            Object core = runtime.type("I3.A").getMethod("e", runtime.type("I3.e0")).invoke(null, per);
            match = runtime.type("I3.L").getMethod("g", runtime.type("I3.y")).invoke(null, core);
        }
        return match;
    }

    private static void validateNotification(JSONObject notification) throws Exception {
        if (notification.length() != 2) throw new IllegalArgumentException("Unexpected notification fields");
        for (String key : new String[]{"title", "content"}) {
            Object value = notification.get(key);
            if (!(value instanceof String)) throw new IllegalArgumentException("Notification must be text");
            String text = (String)value;
            if (text.trim().isEmpty() || text.codePointCount(0, text.length()) > (key.equals("title") ? 80 : 500))
                throw new IllegalArgumentException("Notification text limit");
            for (int i = 0; i < text.length(); i++)
                if (Character.isISOControl(text.charAt(i)) && text.charAt(i) != '\n') throw new IllegalArgumentException("Notification control character");
        }
    }

    private void sendTestText() {
        if (textAttempted || finished || finishing) return;
        textAttempted = true;
        try {
            notificationUid = Integer.toString(1 + new java.security.SecureRandom().nextInt(2147483646));
            String marker = notificationUid.substring(Math.max(0, notificationUid.length() - 4));
            String title = "雷鸟安卓测试 " + marker;
            String content = "你好，雷鸟\n浏览器预览与镜片核对";
            if (customNotification != null) {
                title = customNotification.getString("title");
                content = customNotification.getString("content");
            }
            JSONObject json = new JSONObject().put("notificationUID", notificationUid).put("appId", getPackageName())
                .put("appName", "RayNeo Lab").put("title", title).put("subtitle", "").put("content", content)
                .put("timestamp", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(new Date()))
                .put("category", 0).put("reply", false).put("type", 1);
            result.put("submitted_text", new JSONObject().put("title", title).put("content", content).put("uid", notificationUid));
            sendBusiness("NOTIFICATION", 2, json, activeCommand == null ? "test-text" : activeCommand);
        } catch (Throwable e) { complete("failed", rootError(e)); }
    }

    private void sendBusiness(String businessName, int type, JSONObject json, String id) throws Exception {
        result.put("business_submit_total", result.optLong("business_submit_total") + 1);
        if (businessName.equals("VOICE_ASSISTANT") && type == 2 && json.optInt("rc") == 1)
            result.put("recorder_start_requests_total", result.optLong("recorder_start_requests_total") + 1);
        Class<?> businessType = runtime.type("S3.h"), messageType = runtime.type("I3.P");
        Object business = businessType.getMethod("valueOf", String.class).invoke(null, businessName);
        Object priority = runtime.type("I3.b").getField("d").get(null);
        // Match the iOS recorder/exit fixtures, including their explicit empty field 4.
        byte[] payload = BusinessEnvelope.encode(type, json.toString(), businessName.equals("VOICE_ASSISTANT") && (type == 2 || type == 7 || type == 12));
        Object message = messageType.getConstructor(byte[].class, String.class, String.class, businessType,
                runtime.type("S3.Q"), runtime.type("I3.b"), boolean.class, boolean.class,
                runtime.type("T3.p"), runtime.type("kotlin.jvm.functions.Function2"))
            .newInstance(payload, id, field(targetDevice, "a"), business, null, priority, false, !businessName.equals("VOICE_ASSISTANT")&&!businessName.equals("RECORDING_SERVICE"), null, null);
        Class<?> callbackType = runtime.type("kotlin.jvm.functions.Function2");
        Object callback = Proxy.newProxyInstance(runtime.loader, new Class<?>[]{callbackType}, (proxy, method, args) -> {
            if (method.getName().equals("invoke")) {
                String error = args[1] instanceof Enum ? ((Enum<?>)args[1]).name() : args[1] == null ? null : "unknown";
                handler.post(() -> {
                        if (finished || finishing) return;
                        if(id.startsWith("forward-")){
                            JSONObject timing=PhoneNotifications.sent(id,error==null);
                            try{result.put("phone_notification_callbacks",result.optLong("phone_notification_callbacks")+1);
                                result.put("phone_notification_timing",timing);
                                stage("phone_notification_timing",timing);
                                result.put("phone_notification_last_success",error==null);stage("phone_notification_callback",error==null);}catch(Exception ignored){}
                            return;
                        }
                        if(error!=null&&recorder!=null)recorder.sendFailed(id);
                    try {
                        if (voiceCommand != null && id.equals("voice-wake-" + voiceCommand) && voicePreparing) {
                            if (error != null) { failVoicePreparation(voiceCommand, "开启唤醒发送失败"); return; }
                            voicePreparing = false; voiceArmed = true;
                            result.getJSONObject("voice_test").put("phase", "awaiting_wakeup"); stage("voice_waiting_for_wakeup", true);
                            if (standbyEnabled && "voice-standby".equals(activeCommandKind)) {
                                result.getJSONObject("last_command").put("status", "completed"); rearmStandby(); return;
                            }
                            final String armedId = voiceCommand;
                            handler.postDelayed(() -> { if (voiceArmed && !voiceRecording && armedId.equals(voiceCommand)) finishVoice("No wakeup within 90 seconds"); }, 90000);
                        }
                        if ((id.equals("test-text") || (id.equals(activeCommand) && ("notify".equals(activeCommandKind) || "voice-cloud".equals(activeCommandKind) || "voice-ble".equals(activeCommandKind) || "voice-native".equals(activeCommandKind)))) && error == null) result.put("text_send_completed", true);
                        if (id.equals(activeCommand) && "wake".equals(activeCommandKind))
                            result.getJSONObject("voice_wakeup_request").put("send_completed", error == null);
                        if (id.equals(activeCommand) && ("notify".equals(activeCommandKind) || "voice-cloud".equals(activeCommandKind) || "voice-ble".equals(activeCommandKind) || "voice-native".equals(activeCommandKind) || "wake".equals(activeCommandKind) || error != null))
                            result.getJSONObject("last_command").put("status", error == null ? "completed" : "failed");
                        if (id.equals(activeCommand) && ("voice-cloud".equals(activeCommandKind) || "voice-ble".equals(activeCommandKind) || "voice-native".equals(activeCommandKind))) {
                            JSONObject cloud = result.getJSONObject("glasses_cloud");
                            cloud.put("delivery", new JSONObject().put("command_id", id).put("session_id", result.getString("session_id"))
                                .put("notification_uid", notificationUid).put("status", error == null ? "completed" : "failed"));
                            result.getJSONObject("voice_test").put("phase", error == null ? "finished" : "failed"); writeCloudResult(cloud);
                            if (error == null && standbyEnabled) rearmStandby();
                        }
                        if (voiceCommand != null && id.equals("voice-stop-" + voiceCommand))
                            result.getJSONObject("voice_test").put("stop_send_completed", error == null);
                        stage("message_send_callback", new JSONObject().put("id", id).put("error", error == null ? JSONObject.NULL : error));
                        Runnable next = sendContinuations.remove(id);
                        if (error == null && next != null) next.run();
                        if (error != null) complete("failed", "Business send failed: " + error);
                    } catch (Throwable e) { complete("failed", rootError(e)); }
                });
            }
            return runtime.type("ca.x").getField("a").get(null);
        });
        stage("message_submit_attempt", new JSONObject().put("id", id).put("business", businessName).put("type", type)
                .put("command", json.optString("cmd")).put("bytes", payload.length).put("prefer_low_power", !businessName.equals("VOICE_ASSISTANT")&&!businessName.equals("RECORDING_SERVICE")));
        runtime.core.getClass().getMethod("u", messageType, callbackType).invoke(runtime.core, message, callback);
        stage("message_submitted", new JSONObject().put("id", id).put("business", businessName).put("type", type));
    }

    private void businessEvent(Map<?, ?> event) {
        if (finished || finishing) return;
        try {
            String kind = String.valueOf(event.get("eventType"));
            if (!"messageReceived".equals(kind)) {
                stage("bridge_event", kind); return;
            }
            Object raw = event.get("message");
            if (!(raw instanceof Map)) return;
            Map<?, ?> message = (Map<?, ?>)raw;
            if (!String.valueOf(field(targetDevice, "a")).equals(String.valueOf(message.get("deviceId")))) return;
            String business = String.valueOf(message.get("businessId"));
            if (!(message.get("payload") instanceof byte[])) return;
            if(business.equals("RECORDING_SERVICE")){
                BusinessEnvelope recordingWire=BusinessEnvelope.decodeRecording((byte[])message.get("payload"),recorder!=null&&recorder.captures());
                try{
                    JSONObject recordJson=new JSONObject(recordingWire.json);
                    JSONObject meta=new JSONObject().put("type",recordingWire.type).put("keys",recordJson.names()).put("data_bytes",recordingWire.dataBytes);
                    for(String key:new String[]{"action","code","completed","offset","time","mode","state","type"})if(recordJson.has(key))meta.put("json_"+key,recordJson.opt(key));
                    meta.put("uuid_matches",recorder!=null&&recordJson.optString("uuid").equals(recorder.state().optString("id")));
                    result.put("recording_event",meta);
                    if(recorder!=null)recorder.event(recordingWire);
                    if(recordingWire.type!=3)stage("recording_control_received",meta);
                }finally{if(recordingWire.audio!=null)Arrays.fill(recordingWire.audio,(byte)0);}
                return;
            }
            if (!business.equals("LAUNCHER") && !business.equals("NOTIFICATION") && !business.equals("VOICE_ASSISTANT")) {
                // Inspect only categories and lengths; other business payloads may contain identities.
                if (business.matches("[A-Z_]{1,40}")) {
                    JSONObject counts = result.optJSONObject("other_business_counts");
                    if (counts == null) { counts = new JSONObject(); result.put("other_business_counts", counts); }
                    if (counts.length() < 40 || counts.has(business)) {
                        int count = counts.optInt(business) + 1; counts.put(business, count);
                        if (count == 1) stage("other_business_received", new JSONObject().put("business", business).put("bytes", ((byte[])message.get("payload")).length));
                    }
                }
                return;
            }
            BusinessEnvelope wire = BusinessEnvelope.decode((byte[])message.get("payload"));
            if (business.equals("VOICE_ASSISTANT")) {
                // Channel observer sees these before native plugin audio filtering.
                if (channelObserver == null) voiceEvent(wire);
                return;
            }
            JSONObject json = new JSONObject(wire.json);
            if (business.equals("NOTIFICATION")) {
                if (notificationUid != null && notificationUid.equals(json.optString("notificationUID")) && (wire.type == 3 || wire.type == 4)) {
                    JSONObject reply = new JSONObject().put("business", business).put("type", wire.type).put("uid_matches", true);
                    for (String key : new String[]{"state", "cmd"}) if (json.opt(key) instanceof Number) reply.put(key, json.get(key));
                    result.put("notification_reply", reply);
                    stage("message_received", reply);
                }
                return;
            }
            String command = json.optString("cmd");
            JSONObject summary = new JSONObject().put("business", business).put("type", wire.type).put("command", command)
                .put("keys", json.names());
            JSONObject general = json.optJSONObject("generalStatus");
            if (statusQuerySent && wire.type == 1 && general != null) {
                result.put("status_reply_received", true);
                summary.put("matched_status_query", true).put("general_status_keys", general.names());
                JSONObject safe = new JSONObject();
                for (String key : new String[]{"battery", "brightness"}) if (general.opt(key) instanceof Number) safe.put(key, general.get(key));
                result.put("reported_status", safe);
                if ("session".equals(businessProbe)) {
                    if (!result.optString("status").equals("sdk_session_ready")) {
                        result.put("status", "sdk_session_ready").put("connect_attempted", true)
                            .put("session_seconds", persistentSession?0:sessionSeconds).put("persistent_session",persistentSession).put("target_address", address);
                        if(persistentSession)assistantPrefs().edit().putBoolean("auto_connect",true).apply();
                        stage("session_ready", true);
                        PhoneNotifications.attach(phoneNotificationSink);
                        display.setText(persistentSession?"眼镜已连接\n语音自动待命，可返回 App 操作。":"雷鸟调试连接，限时 "+sessionSeconds/60+" 分钟");
                        if(!persistentSession)getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        handler.post(this::sessionTick);
                        if(!persistentSession)handler.postDelayed(() -> complete("sdk_session_completed", "Session expired"), sessionSeconds * 1000L);
                    }
                    if (activeCommand != null && "status".equals(activeCommandKind)) result.getJSONObject("last_command").put("status", "completed");
                }
                if ("text".equals(businessProbe) && !textAttempted) handler.postDelayed(this::sendTestText, 1500);
            }
            JSONObject payload = json.optJSONObject("payload");
            if (payload != null) summary.put("payload_keys", payload.names());
            if (wire.type == 4 || command.equals("sync_ai_settings") || command.equals("set_ai_voice_wakeup")) {
                JSONObject settings = new JSONObject();
                inspectControls(json, "", 0, settings);
                result.put("reported_ai_settings", settings);
                summary.put("controls", settings);
            }
            if (command.equals("setup_control")) {
                JSONObject controls = new JSONObject(); inspectControls(json, "", 0, controls);
                result.put("reported_setup", controls); summary.put("controls", controls);
            }
            if (command.equals("screen_status") && payload != null && payload.opt("value") instanceof Number)
                result.put("screen_raw", payload.get("value"));
            if (command.equals("glass_preview") && payload != null) {
                JSONObject page = new JSONObject(payload.optString("data", "{}"));
                JSONObject safe = new JSONObject();
                for (String key : new String[]{"scence", "scene", "index"}) if (page.opt(key) instanceof Number) safe.put(key, page.get(key));
                JSONObject app = page.optJSONObject("app");
                if (app != null && app.opt("action") instanceof Number) safe.put("action", app.get("action"));
                result.put("reported_page", safe);
            }
            stage("message_received", summary);
        } catch (Throwable e) { complete("failed", rootError(e)); }
    }

    // Settings structure and bounded control values only; never arbitrary strings or credentials.
    private static void inspectControls(JSONObject value, String path, int depth, JSONObject out) throws Exception {
        if (depth > 3 || out.length() >= 60) return;
        java.util.Iterator<String> keys = value.keys();
        int count = 0;
        while (keys.hasNext() && count++ < 35 && out.length() < 60) {
            String key = keys.next();
            if (!key.matches("[A-Za-z_][A-Za-z_0-9]{0,63}")) continue;
            String full = path.isEmpty() ? key : path + "." + key;
            Object item = value.opt(key);
            out.put(full, item instanceof JSONObject ? "object" : "present");
            if (item instanceof Boolean && (key.equals("voiceWakeup") || key.equals("voice_wakeup") || key.equals("ai_voice_wakeup"))) out.put(full, item);
            boolean control = path.equals("payload") && (key.equals("mode") || key.equals("value"));
            boolean aiControl = path.equals("payload.data") && (key.equals("ai_voice_wakeup") || key.equals("ai_wakeup_word")
                    || key.equals("ai_switch_state") || key.equals("ai_shortcuts_state") || key.equals("ai_work_mode") || key.equals("ai_state"));
            if (item instanceof Number && (control || aiControl)) {
                double scalar = ((Number)item).doubleValue();
                if (scalar >= 0 && scalar <= 255) out.put(full, item);
            }
            if (item instanceof JSONObject) inspectControls((JSONObject)item, full, depth + 1, out);
            else if (key.equals("data") && path.equals("payload") && item instanceof String && ((String)item).length() <= 8192) {
                try { inspectControls(new JSONObject((String)item), full, depth + 1, out); }
                catch (org.json.JSONException ignored) { /* Plain text is deliberately not retained. */ }
            }
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // Claim before publishing status, scheduling the watchdog or touching the SDK.
        if (!sessions.claim(this)) {
            finished = true;
            voiceWorker.shutdownNow();
            finish();
            return;
        }
        connectionActive=true;
        startedAt = SystemClock.elapsedRealtime();
        android.widget.LinearLayout panel = new android.widget.LinearLayout(this); panel.setOrientation(1);
        display = new TextView(this); display.setPadding(24, 48, 24, 24);
        android.widget.ScrollView details = new android.widget.ScrollView(this); details.addView(display);
        panel.addView(details, new android.widget.LinearLayout.LayoutParams(-1, 0, 1));
        android.widget.Button cloud = new android.widget.Button(this); cloud.setText("模型与语音测试 / Key 配置");
        cloud.setOnClickListener(v -> startActivity(new android.content.Intent(this, CloudActivity.class))); panel.addView(cloud); setContentView(panel);
        display.setText("雷鸟 SDK 测试\n正在检查指定眼镜与本机权限…");
        if (getIntent().getBooleanExtra("companion_ui", false)) getWindow().getDecorView().post(() ->
            startActivity(new android.content.Intent(this, CloudActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
        persistentSession=getIntent().getBooleanExtra("companion_ui",false)&&"session".equals(getIntent().getStringExtra("business_probe"));
        if(!persistentSession)new Thread(() -> {
            long timeout = "session".equals(getIntent().getStringExtra("business_probe"))
                    ? (Math.max(60, Math.min(1800, getIntent().getIntExtra("session_seconds", 1800))) + 70L) * 1000 : 80000;
            try { Thread.sleep(timeout); } catch (InterruptedException ignored) {}
            handler.post(() -> {
                // Lifecycle and timeout decision execute on the same main thread.
                if (!finished && !finishing && sessions.owns(this)) android.os.Process.killProcess(android.os.Process.myPid());
            });
        }, "sdk-probe-watchdog").start();
        try {
            address = getIntent().getStringExtra("target_address");
            connectMode = getIntent().getBooleanExtra("connect", false);
            businessProbe = getIntent().getStringExtra("business_probe");
            sessionSeconds = getIntent().getIntExtra("session_seconds", 1800);
            if (sessionSeconds < 60 || sessionSeconds > 1800) throw new IllegalArgumentException("Session duration limit");
            if (businessProbe != null && (!connectMode || !(businessProbe.equals("status") || businessProbe.equals("text") || businessProbe.equals("session")))) throw new IllegalArgumentException("Invalid business probe");
            if (getIntent().getBooleanExtra("custom_notification", false)) {
                if (!"text".equals(businessProbe)) throw new IllegalArgumentException("Custom notification requires text mode");
                byte[] bytes;
                try (FileInputStream input = openFileInput("notification.json"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[1024]; int size;
                    while ((size = input.read(buffer)) != -1) {
                        out.write(buffer, 0, size);
                        if (out.size() > 8192) throw new IllegalArgumentException("Notification input too large");
                    }
                    bytes = out.toByteArray();
                }
                customNotification = new JSONObject(new String(bytes, "UTF-8"));
                validateNotification(customNotification);
            }
            result.put("status", "running").put("mode", businessProbe != null ? "sdk-" + businessProbe : connectMode ? "sdk-connect" : "sdk-discover")
                .put("connect_attempted", false).put("target_discovered", false).put("auth_success_callback", false)
                .put("ble_connected_callback", false)
                .put("pid", android.os.Process.myPid()).put("session_id", UUID.randomUUID().toString())
                .put("sdk_int", Build.VERSION.SDK_INT).put("package", getPackageName());
            stage("begin", true);
            if (Build.VERSION.SDK_INT < 31 || !BluetoothAdapter.checkBluetoothAddress(address))
                throw new IllegalArgumentException("Android 12+ and an explicit Bluetooth address required");
            for (String permission : new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN})
                if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) throw new SecurityException("Missing " + permission);
            manager = getSystemService(BluetoothManager.class);
            if (manager == null || manager.getAdapter() == null || !manager.getAdapter().isEnabled())
                throw new IllegalStateException("Bluetooth unavailable");
            if ("session".equals(businessProbe)) {
                startForegroundService(new android.content.Intent(this, ConnectionService.class));
                connectionServiceStarted = true;
            }
            BluetoothDevice systemDevice = manager.getAdapter().getRemoteDevice(address);
            boolean systemConnected = systemConnected();
            int bondState = systemDevice.getBondState();
            stage("system_preflight", new JSONObject().put("gatt_connected", systemConnected).put("bond_state", bondState));
            boolean hasSaved = !getSharedPreferences("rayneo_net_bonded_devices", MODE_PRIVATE).getAll().isEmpty();
            if (connectMode && (systemConnected || (!hasSaved && (!getIntent().getBooleanExtra("pairing_ready", false) || bondState != BluetoothDevice.BOND_NONE))
                    || (hasSaved && bondState != BluetoothDevice.BOND_BONDED))) {
                complete("blocked_or_failed", "First connection requires confirmed official unbinding, system pairing removed, and pairing mode; no SDK connect attempted"); return;
            }
            runtime = new VendorRuntime(this);
            stage("vendor_initialized_with_own_context", true);
            if (hasSaved) {
                if (!"session".equals(businessProbe)) { complete("blocked_or_failed", "Saved binding preserved; use session mode"); return; }
                targetDevice = savedTarget();
                if (targetDevice == null) { complete("blocked_or_failed", "No matching saved target; other bindings preserved"); return; }
                reconnectMode = true;
                result.put("reconnect_from_saved", true);
                stage("saved_target_loaded", true);
            }
            // Original SDK's documented-by-instructions BLE-only switch suppresses
            // automatic HIGH_AP/RFCOMM opening after authentication (L.n / j0).
            java.util.concurrent.atomic.AtomicBoolean bleOnly = (java.util.concurrent.atomic.AtomicBoolean)
                runtime.type("I3.u").getField("b").get(null);
            bleOnly.set(true);
            stage("sdk_ble_only", bleOnly.get());
            SharedPreferences prefs = (SharedPreferences) runtime.type("I3.l").getMethod("e").invoke(null);
            if (prefs != getSharedPreferences("rayneo_net_bonded_devices", MODE_PRIVATE)) throw new IllegalStateException("Wrong cache context");
            Class<?> listenerType = runtime.type("T3.g");
            listener = Proxy.newProxyInstance(runtime.loader, new Class<?>[]{listenerType}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    if (method.getName().equals("equals")) return proxy == args[0];
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    return "SdkProbeConnectionListener";
                }
                handler.post(() -> onEvent(method.getName(), args)); return null;
            });
            Object registry = field(runtime.core, "b");
            listeners = (List<?>) field(registry, "a");
            registry.getClass().getMethod("a", Object.class).invoke(registry, listener);
            stage("listener_registered", listeners.contains(listener));
            if (businessProbe != null) {
                channelObserver = new VendorChannelObserver(runtime, address, handler, new VendorChannelObserver.Receiver() {
                    public boolean wantsAudio() { return cloudVoice && voiceRecording && !finished; }
                    public void metadata(String device, String business, String route, BusinessEnvelope wire) {
                        if (finished || finishing || targetDevice == null) return;
                        try {
                            if (!device.equals(String.valueOf(field(targetDevice, "a")))) return;
                            int count = result.optInt("channel_observer_packets") + 1;
                            result.put("channel_observer_packets", count);
                            if (count == 1) stage("channel_observer_first_packet", new JSONObject().put("business", business).put("type", wire.type).put("route", route));
                            if (business.equals("VOICE_ASSISTANT")) { result.put("voice_receive_route", route); voiceEvent(wire); }
                            // Recording is delivered through businessEvent once; the raw observer
                            // must not feed a second copy into an already finalizing recording.
                        } catch (Exception e) { complete("failed", rootError(e)); }
                    }
                    public void error(Throwable e) { complete("failed", rootError(e)); }
                });
                stage("channel_observer_registered", true);
                messageBridge = new VendorMessageBridge(runtime, this, handler, new VendorMessageBridge.Receiver() {
                    public void event(Map<?, ?> event) { businessEvent(event); }
                    public void error(Throwable e) { complete("failed", rootError(e)); }
                });
            stage("message_bridge_registered", true);
            }
            result.put("sdk_app_state", runtime.type("c4.e").getMethod("b").invoke(null));
            // Resolve the exact connection surface before any binding switch.
            runtime.type("I3.L").getMethod("w", runtime.type("I3.D"));
            runtime.type("I3.L").getMethod("i", String.class, runtime.type("S3.B"));
            stage("connection_methods_resolved", true);
            routeObserver = new VendorRouteObserver(runtime, () -> {
                try { return targetDevice == null ? "" : String.valueOf(field(targetDevice, "a")); }
                catch (Exception e) { return ""; }
            }, handler, (route, packet, request) -> {
                if (finished || finishing) return;
                try {
                    JSONObject event = new JSONObject().put("route", route).put("packet_id", packet).put("request_id", request);
                    result.put("last_send_route", event); stage("sdk_selected_send_route", event);
                } catch (Exception e) { complete("failed", rootError(e)); }
            });
            stage("route_observer_registered", true);
            if (reconnectMode) { handler.post(this::tick); return; }
            Class<?> entry = runtime.type("com.rayneo.rayneo_venus_sdk_plugin.h$b");
            Object filters = Array.newInstance(runtime.type("S3.F"), 0);
            discoveryStarted = true;
            entry.getMethod("b", filters.getClass(), String[].class).invoke(entry.getField("a").get(null), filters, new String[0]);
            boolean scanStarted = runtime.type("V3.G").getField("d").getBoolean(null);
            stage("sdk_discovery_started", scanStarted);
            if (!scanStarted) throw new IllegalStateException("SDK did not start scanner");
            handler.post(this::tick);
            handler.postDelayed(() -> {
                if (!connectAttempted) complete(connectMode ? "blocked_or_failed" : "sdk_discovery_completed", connectMode ? "Target not found in 15 seconds" : null);
            }, 15000);
        } catch (Throwable e) { complete("failed", rootError(e)); }
    }

    @Override protected void onDestroy() {
        if (!finished) complete("failed", "Activity destroyed");
        super.onDestroy();
    }
}
