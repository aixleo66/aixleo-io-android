package dev.xr.rayneo.probe;

import android.Manifest;
import android.bluetooth.*;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

/** One glasses session: SDK discovery, BLE authentication and the business link to one target.
 *
 * <p>Since 2026-09-23 (plan 0.5c) this is <b>not an Activity</b>: it is a plain Context wrapper that
 * {@link ConnectionService} (the connectedDevice foreground service) creates per session and hosts.
 * As an Activity it needed MIUI's "show on top from background" permission for every automatic
 * reconnect, and pressing Back on its debug page ended the link (both measured 09-23 on device).
 * The class keeps its old name so the 50+ source-level tests that read this file stay unchanged;
 * renaming it is a separate mechanical change. */
public final class SdkProbeActivity extends android.content.ContextWrapper {
    private static final SessionOwnership sessions = new SessionOwnership();
    static volatile boolean connectionActive;
    private boolean connectionServiceStarted;
    /** When this session first became ready (wall clock); 0 if it never did. Feeds reconnect backoff. */
    private long sessionReadyAtMs;
    private long startedWallMs;
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
    /** What the old debug page showed; kept as the last status line, no longer drawn anywhere. */
    private static final class StatusText { volatile String text = ""; void setText(CharSequence value) { text = String.valueOf(value); } }
    private final StatusText display = new StatusText();
    private Intent startIntent;
    private Intent getIntent() { return startIntent; }
    SdkProbeActivity(Context host) { super(host); }
    boolean ended() { return finished || finishing; }
    private String address;
    private String businessProbe;
    private boolean authenticated;
    private boolean statusQuerySent, textAttempted;
    private LabBrightnessTrial brightnessTrial;
    private LabWakeTrial wakeTrial;
    private boolean wakePublishedDone;
    private String wakePublishedPhase="";
    private LabHeadTrial headTrial;
    private boolean headPublishedDone;
    private String headPublishedPhase="";
    private LabCrownTrial crownTrial;
    private boolean crownPublishedDone;
    private String crownPublishedPhase="";
    private LabAutoLockTrial autoLockTrial;
    private boolean autoLockPublishedDone, settingsQueryUncertain;
    private String autoLockPublishedPhase="";
    private boolean brightnessDonePublished;
    private String notificationUid;
    private JSONObject customNotification;
    private LabDisplayTrial displayTrial;
    private LabAnswerTrial answerTrial;
    private volatile LabAsrTrial asrTrial;
    private StreamingAsr labAsrStream;
    private NativeAnswerRound labNativeRound;
    private StreamingAsr labNativeStopStream;
    private boolean labNativeStandby;
    private int labNativeAutoExitSeconds=15;
    private int labNativeFollowupSeconds=10;
    private boolean labNativeFollowupEnabled=true;
    // ASR 中间结果下发节流。官方实测一轮下发 3-5 次且随识别增量推送，
    // 自有原为每 500ms 定时一次导致跳字明显。这里只按稳定前缀、空闲间隔与每轮上限控制刷新。
    private int asrPartialPrefixStep=4;
    private int asrPartialIdleMs=1200;
    private int asrPartialMaxPerRound=12;
    private int labNativeContinuationLinkType;
    private boolean labNativeContinuationCandidate;
    private String labNativeContinuationUser="",labNativeContinuationDialog="";
    private Runnable labNativeAutoExit;
    // The answer is sent but the glasses are still scrolling it. Windows must not start here:
    // a long answer scrolls for ~17s while a 15s window started at send time expires mid-read
    // and exits the page. assistant_type_11 is the glasses reporting the scroll finished.
    private boolean awaitingDisplayComplete;
    private Runnable displayCompleteFallback;
    // Default kept low on purpose: until speechMode=DUPLEX is wired up the glasses never send
    // assistant_type_11, so this wait is the normal path, not the exception. At 60s a long answer
    // kept the page up for ~75s. Raise it once the report is proven to arrive.
    private int labNativeDisplayWaitSeconds=60;
    private int labNativeAnswerChunks=1;
    private Runnable labNativeExitConfirmation;
    private final AssistantConversation labAssistantConversation=new AssistantConversation();
    private boolean labAssistantConversationActive;
    private final String labNativeUser=UUID.randomUUID().toString(),labNativeDialog=UUID.randomUUID().toString();
    private boolean labNativeMode(){return getPackageName().equals("dev.xr.rayneo.sdklab")&&"voice-native".equals(activeCommandKind);}
    private String asrPublished="";
    private int sessionSeconds;
    private boolean persistentSession;
    private long nextStandbyAttempt;
    private SharedPreferences assistantPrefs(){return getSharedPreferences("assistant",MODE_PRIVATE);}
    private void beginStandby(String id)throws Exception{
        JSONObject settings=CloudConfig.load(this);
        labNativeAutoExitSeconds=settings.optInt("assistant_auto_exit_seconds",15);
        labNativeDisplayWaitSeconds=displayWaitSeconds(settings);
        labNativeFollowupSeconds=settings.optInt("assistant_followup_seconds",10);
        labNativeFollowupEnabled=settings.optBoolean("assistant_followup_enabled",true);
        asrPartialPrefixStep=settings.optInt("asr_partial_prefix_step",4);
        asrPartialIdleMs=settings.optInt("asr_partial_idle_ms",1200);
        asrPartialMaxPerRound=settings.optInt("asr_partial_max_per_round",12);
        // The destination is chosen after final ASR; each answer client checks its own credentials.
        if(settings.optString("dashscope_key").isEmpty()){
            result.getJSONObject("last_command").put("status","failed");stage("standby_missing_config",true);return;
        }
        standbyEnabled=true;standbyReady=false;standbyRounds=0;standbyControl=id;
        labNativeStandby=getPackageName().equals("dev.xr.rayneo.sdklab");
        if(labNativeStandby){resetLabNativeRound();startLabAssistantConversation("standby_started");}
        cloudVoice=true;nativeReply=true;voiceCommand=id;voiceArmed=false;voiceRecording=false;
        voicePackets=0;voiceBytes=0;voiceAfterStop=0;OpusAudio.clear(voiceAudio);
        result.put("voice_test",new JSONObject().put("phase","preparing").put("audio_saved",false).put("audio_uploaded",false)
            .put("cloud_enabled",true).put("audio_source","glasses").put("trigger","glasses_wakeup"));
        publishStandby("preparing");prepareGlassesVoice(id);
    }
    private void restoreStandbyIfNeeded()throws Exception{
        if ((asrTrial != null && !asrTrial.done) || displayTrial != null || answerTrial != null || autoLockPending() || crownPending() || headPending() || wakePending()) return;
        if(!persistentSession||!assistantPrefs().getBoolean("auto_standby",true)||standbyEnabled||voiceArmed||voicePreparing||voiceRecording
            ||recordingPreparing||(recorder!=null&&recorder.busy())||SystemClock.elapsedRealtime()<nextStandbyAttempt
            ||SystemClock.elapsedRealtime()<nativeDisplayUntil||labNativeReadingActive())return;
        if(!connectionReady())return;
        JSONObject prior=result.optJSONObject("last_command");
        if(prior!=null&&"pending".equals(prior.optString("status")))return;
        nextStandbyAttempt=SystemClock.elapsedRealtime()+60000;
        activeCommand=UUID.randomUUID().toString();activeCommandKind="voice-standby";
        result.put("last_command",new JSONObject().put("id",activeCommand).put("kind",activeCommandKind).put("status","pending").put("started_ms",SystemClock.elapsedRealtime()));
        stage("standby_auto_restore",true);beginStandby(activeCommand);
    }
    private boolean reconnectMode, pairingRequested, pairingModeAttempt, repairUnbonded;
    private boolean labExistingSystemBond;
    private boolean pairingWindowOpen, bondEvidenceRequired;
    private boolean connectionReady() throws Exception {
        return ConnectionReadiness.ready(authenticated, result.optBoolean("status_reply_received"),
            manager.getAdapter().getRemoteDevice(address).getBondState(),
            bondEvidenceRequired || !reconnectMode || pairingModeAttempt || repairUnbonded, result.optBoolean("sdk_bond_success"));
    }
    private boolean voiceArmed;
    private boolean voicePreparing;
    private volatile boolean voiceRecording, cloudVoice;
    private final List<byte[]> voiceAudio = new ArrayList<>();
    private final java.util.concurrent.ExecutorService voiceWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private int voicePackets, voiceBytes, voiceAfterStop;
    private String voiceCommand;
    private VoiceRoundJournal voiceRounds;
    private void beginVoiceRound() {
        if(voiceRounds==null)voiceRounds=new VoiceRoundJournal(new File(getFilesDir(),"voice-rounds"));
        voiceRounds.begin(result.optString("session_id"),voiceCommand,SystemClock.elapsedRealtime());
    }
    private void voiceTiming(String command,String name,long at) {
        if(voiceRounds==null)return;
        voiceRounds.mark(command,name,at);publishVoiceTiming();
    }
    private void publishVoiceTiming() {
        if(voiceRounds!=null)try{result.put("voice_round_timing",voiceRounds.snapshot());}catch(Exception ignored){}
    }
    private void endVoiceRound(String command,String outcome) {
        cancelVoiceTodoSync(command,outcome);
        if(voiceRounds==null)return;
        voiceRounds.finish(command,outcome,SystemClock.elapsedRealtime());publishVoiceTiming();
    }

    private boolean nativeReply;
    private long voiceStopAt;
    private long nativeDisplayUntil;
    private long nativeFollowupUntil;
    private final PhoneNotifications.Sink phoneNotificationSink=this::forwardPhoneNotification;
    private boolean forwardPhoneNotification(JSONObject payload,String id)throws Exception{
        if(asrTrial!=null&&!asrTrial.done)return dropNotification("asr_trial_active");
        if(autoLockPending())return dropNotification("auto_lock_pending");
        if(crownPending()||headPending()||wakePending())return dropNotification("input_trial_pending");
        if(displayTrial!=null)return dropNotification("display_trial_active");
        if(finished||finishing)return dropNotification("session_ending");
        if(!connectionReady())return dropNotification("connection_not_ready");
        if(voiceRecording||voicePreparing||recordingPreparing||(recorder!=null&&recorder.busy()))
            return dropNotification("capture_active");
        if(standbyEnabled&&!standbyReady)return dropNotification("standby_not_ready");
        if(SystemClock.elapsedRealtime()<nativeDisplayUntil||labNativeReadingActive())
            return dropNotification("lens_showing_answer");
        if(commandStillOwnsTheLink())return dropNotification("command_pending");
        notificationUid=payload.optString("notificationUID");
        result.remove("notification_reply");
        sendBusiness("NOTIFICATION",2,payload,id);return true;
    }
    /** Whether an unfinished command should still hold back other traffic.
     *
     * <p>this used to be "is last_command pending", with nothing that ever expired the
     * state, so one interrupted voice round silenced phone notifications for the rest of the
     * session. The command is left alone -- it may still complete -- but once it is past the
     * deadline the host itself would have given up on, it stops speaking for the link. */
    private boolean commandStillOwnsTheLink()throws Exception{
        JSONObject command=result.optJSONObject("last_command");
        if(command==null||!"pending".equals(command.optString("status")))return false;
        long started=command.optLong("started_ms",-1);
        long age=started<0?-1:SystemClock.elapsedRealtime()-started;
        if(!CommandDeadline.expired(command.optString("kind"),age))return true;
        if(!command.optBoolean("deadline_passed")){
            command.put("deadline_passed",true);
            result.put("commands_past_deadline_total",result.optLong("commands_past_deadline_total")+1);
            stage("command_past_deadline",new JSONObject().put("kind",command.optString("kind"))
                .put("id",command.optString("id")).put("age_ms",age)
                .put("budget_ms",CommandDeadline.budgetMs(command.optString("kind"))));
        }
        return false;
    }
    /** A refused notification used to vanish without a trace, so "notifications stopped arriving"
     * could not be diagnosed from the session at all. Counting by reason costs
     * nothing and turns the next report into one lookup. */
    private boolean dropNotification(String reason)throws Exception{
        result.put("notifications_dropped_total",result.optLong("notifications_dropped_total")+1);
        JSONObject byReason=result.optJSONObject("notifications_dropped_by_reason");
        if(byReason==null){byReason=new JSONObject();result.put("notifications_dropped_by_reason",byReason);}
        byReason.put(reason,byReason.optLong(reason)+1);
        result.put("last_notification_drop",new JSONObject().put("reason",reason)
            .put("elapsed_ms",SystemClock.elapsedRealtime()-startedAt));
        return false;
    }
    private boolean standbyEnabled, standbyReady;
    private int standbyRounds;
    private String standbyControl;
    private CloudClient.Cancellation cloudCancellation;
    private StreamingAsr streamingAsr;
    private GlassesRecorder recorder;
    private boolean recordingTransportOwned;
    private boolean recordingPreparing;
    private String pendingDeviceRecordingId;
    private long recordingTransportGeneration;
    private String recordingNotificationPhase="";
    private void recordingTransportTrace(String event) {
        if(!"dev.xr.rayneo.sdklab".equals(getPackageName()))return;
        try {
            JSONObject current=result.optJSONObject("recording");
            JSONObject item=new JSONObject().put("event",event).put("elapsed_ms",SystemClock.elapsedRealtime()-startedAt)
                .put("session_id",result.optString("session_id")).put("command_id",activeCommand==null?"":activeCommand)
                .put("generation",recordingTransportGeneration).put("owned",recordingTransportOwned)
                .put("requested",sppRequested).put("preparing",recordingPreparing)
                .put("recording_id",current==null?"":current.optString("id"))
                .put("recording_phase",current==null?"":current.optString("phase"))
                .put("spp_auth_callback",result.optBoolean("spp_auth_success_callback"))
                .put("last_spp_state",result.optString("last_spp_state"))
                .put("last_spp_event_ms",result.optLong("last_spp_event_ms",-1))
                .put("last_ble_state",result.optString("last_ble_state"))
                .put("last_ble_event_ms",result.optLong("last_ble_event_ms",-1));
            JSONArray trace=result.optJSONArray("recording_transport_trace");
            if(trace==null){trace=new JSONArray();result.put("recording_transport_trace",trace);}
            trace.put(item);if(trace.length()>40)trace.remove(0);
            result.put("recording_transport",item);stage("recording_transport",item);
        } catch(Exception ignored) { /* Observations must not change transport control. */ }
    }
    private void prepareRecordingTask() throws Exception {
        prepareRecordingTask(null);
    }
    private void prepareRecordingTask(String deviceId) throws Exception {
        if(!connectionReady())throw new IOException("Pairing is not complete");
        if(recordingPreparing||(recorder!=null&&recorder.busy()))throw new IOException("Recording already active");
        if(voiceRecording||voicePreparing)throw new IOException("Finish active voice question first");
        nextStandbyAttempt=0;
        final long generation=++recordingTransportGeneration;
        if(standbyEnabled||voiceArmed)stopStandby();
        recordingPreparing=true;pendingDeviceRecordingId=deviceId;
        result.put("recording",new JSONObject().put("phase","connecting").put("source",deviceId==null?"phone":"glasses_menu").put("bytes",0).put("audio_uploaded",false));stage("recording_preparing",true);
        if(result.optBoolean("spp_auth_success_callback")){recordingTransportTrace("prepare_reuse_auth_callback");startRecordingTask();return;}
        recordingTransportOwned=!sppRequested;sppRequested=true;
        recordingTransportTrace("prepare_request_spp");
        boolean submitted=(Boolean)runtime.type("I3.L").getMethod("f",runtime.type("I3.D")).invoke(null,targetDevice);
        result.put("spp_connect_submitted",submitted);stage("recording_spp_requested",submitted);
        if(!submitted){failRecordingPreparation();return;}
        final long deadline=SystemClock.elapsedRealtime()+20000;
        handler.post(new Runnable(){public void run(){if(finished||finishing||!recordingPreparing||generation!=recordingTransportGeneration)return;try{
            if(result.optBoolean("spp_auth_success_callback")){startRecordingTask();return;}
            if(SystemClock.elapsedRealtime()>deadline){failRecordingPreparation();return;}
            handler.postDelayed(this,250);
        }catch(Exception e){try{failRecordingPreparation();stage("recording_start_failed",e.getClass().getSimpleName());}catch(Exception ignored){}}}});
    }
    private void failRecordingPreparation()throws Exception{
        String deviceId=pendingDeviceRecordingId;pendingDeviceRecordingId=null;recordingPreparing=false;recordingTransportGeneration++;
        if(deviceId!=null)try{sendBusiness("RECORDING_SERVICE",4,new JSONObject().put("uuid",deviceId).put("action",1).put("code",2),"rec-preparation-stop-"+deviceId);}catch(Exception ignored){}
        nextStandbyAttempt=SystemClock.elapsedRealtime()+3000;
        result.put("recording",new JSONObject().put("phase","failed").put("error","录音连接未准备好，请重试。").put("audio_uploaded",false));
        result.getJSONObject("last_command").put("status","failed");stage("recording_transport_failed",true);recordingTransportTrace("preparation_failed");endRecordingTransportUse("preparation_failed");
        publishVoiceRecording(result.getJSONObject("recording"));
    }
    private void cancelRecordingPreparation(JSONObject deviceStop)throws Exception{
        String deviceId=pendingDeviceRecordingId;pendingDeviceRecordingId=null;recordingPreparing=false;recordingTransportGeneration++;
        try{
            if(deviceId!=null)sendBusiness("RECORDING_SERVICE",4,new JSONObject().put("uuid",deviceId)
                .put("action",deviceStop==null?1:2).put("code",deviceStop==null?2:deviceStop.optInt("code",2)),"rec-preparation-stop-"+deviceId);
        }finally{
            recordingTransportTrace("preparation_cancelled");endRecordingTransportUse("preparation_cancelled");nextStandbyAttempt=SystemClock.elapsedRealtime()+3000;
            result.put("recording",new JSONObject().put("phase","cancelled").put("audio_uploaded",false));
            result.getJSONObject("last_command").put("status","completed");stage("record_start_cancelled",true);
            publishVoiceRecording(result.getJSONObject("recording"));
        }
    }
    private void receiveDeviceRecordingStart(JSONObject body)throws Exception{
        if(answerTrial!=null){
            stage("answer_trial_interrupted","Native recording menu requested; no capture started");
            complete("sdk_session_completed","Recording menu interrupted fixed answer trial");return;
        }
        Object value=body.opt("uuid");String deviceId=value instanceof String?(String)value:null;
        if(!GlassesRecorder.validDeviceId(deviceId)){stage("recording_start_ignored","invalid_session");return;}
        if (displayTrial != null) {
            // A physical menu action must not get stuck behind an unattended display trial.
            result.put("display_interrupted_by_recording_menu", true);
            if (displayTrial.owns(activeCommand)) result.getJSONObject("last_command").put("status", "cancelled");
            stage("display_trial_interrupted", "Native recording requested; end trial and its connection without starting audio");
            complete("sdk_session_completed", "Native recording menu interrupted display trial");
            return;
        }
        if(recordingPreparing){stage("recording_start_ignored",deviceId.equals(pendingDeviceRecordingId)?"repeat_preparing":"another_task_preparing");return;}
        if(recorder!=null&&recorder.busy()){
            if(deviceId.equals(recorder.state().optString("id"))&&"glasses_menu".equals(recorder.state().optString("source")))recorder.acceptDeviceStart(deviceId);
            else stage("recording_start_ignored","another_recording_active");
            return;
        }
        JSONObject previous=result.optJSONObject("last_command");
        if((asrTrial!=null&&!asrTrial.done)||displayTrial!=null||!authenticated||!persistentSession||voiceRecording||voicePreparing||(standbyEnabled&&!standbyReady)
            ||SystemClock.elapsedRealtime()<nativeDisplayUntil||labNativeReadingActive()
            ||(previous!=null&&"pending".equals(previous.optString("status")))){
            stage("recording_start_ignored","session_busy_or_not_ready");return;
        }
        if(new File(new File(getFilesDir(),"recordings"),deviceId).exists()){stage("recording_start_ignored","existing_recording_preserved");return;}
        // This is an explicit action in the native menu of the connected glasses.
        // Do not create a second UUID or route it through ASR/model inference.
        activeCommand=UUID.randomUUID().toString();activeCommandKind="record-start";
        result.put("last_command",new JSONObject().put("id",activeCommand).put("kind",activeCommandKind).put("source","glasses_menu").put("status","pending").put("started_ms",SystemClock.elapsedRealtime()));
        stage("recording_device_start_received",true);
        try{prepareRecordingTask(deviceId);}catch(Exception e){failRecordingPreparation();stage("recording_start_failed",e.getClass().getSimpleName());}
    }
    /** The one place that hands the SPP channel back, and since option 甲 the only one: session
     * cleanup. Returns true when the release was submitted.
     *
     * <p>Until 2026-09-21 the recording paths called this too, 1500ms after the audio landed. That
     * handback is what put the glasses into "请将眼镜连接至雷鸟AI", where they answer nothing the
     * user says until the official app reconnects them. The same shape had already been patched
     * around twice without being questioned: a generation guard was added
     * so a stale release would not kill the next recording, and the 2026-09-11 note recorded the
     * glasses demanding the official app while both channels had authenticated. The official app
     * never releases -- it held SPP for 3.5 hours at 1.96 %/h, below our own 3.44 %/h without it --
     * so there is no measured cost to holding, and every objection to holding was checked and found
     * to rest on one unmeasured phrase. Recording completion therefore drops its claim without releasing the shared channel. */
    private boolean submitTransportRelease(String trace){
        try{
            runtime.type("I3.L").getMethod("i",String.class,runtime.type("S3.B")).invoke(null,(String)field(targetDevice,"a"),runtime.type("S3.B").getField("c").get(null));sppRequested=false;recordingTransportOwned=false;
            recordingTransportTrace(trace+"_submitted");
            return true;
        }catch(Exception ignored){
            recordingTransportTrace(trace+"_error");
            return false;
        }
    }
    /** Ends this recording's claim on the channel without handing the channel back.
     *
     * <p>{@code recordingTransportOwned} means "a recording task still needs the transport", which
     * is what gates the expedited standby restore. It stops being true when the recording ends; the
     * channel itself stays up until the session does. */
    private void endRecordingTransportUse(String trace){
        if(!recordingTransportOwned){recordingTransportTrace(trace+"_skipped_not_owned");return;}
        recordingTransportOwned=false;
        recordingTransportTrace(trace+"_transport_kept");
    }
    private static String statusShape(Object value) {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) return "object";
        if (value instanceof JSONArray) return "array";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Number) return "number";
        if (value instanceof String) return "string";
        return "other";
    }

    private SharedPreferences autoLockJournal(){return getSharedPreferences("sdk_lab_auto_lock",MODE_PRIVATE);}
    private boolean autoLockPending(){return getPackageName().equals("dev.xr.rayneo.sdklab")&&autoLockJournal().getBoolean("pending",false);}
    private void beginAutoLockTrial(boolean restoreOnly,boolean observeAssistant)throws Exception{
        if(!getPackageName().equals("dev.xr.rayneo.sdklab")||!connectionReady()||!persistentSession
            ||(!observeAssistant&&(standbyEnabled||voiceArmed))||voicePreparing||voiceRecording||recordingPreparing||(recorder!=null&&recorder.busy())
            ||headPending()||wakePending()||crownPending()||displayTrial!=null||answerTrial!=null||brightnessJournal().getBoolean("pending",false)
            ||(settingsQuery!=null&&!settingsQuery.done)||settingsQueryUncertain)
            throw new IllegalStateException("Requires idle persistent ready Lab with unambiguous queries");
        final SharedPreferences journal=autoLockJournal();
        final String ownerSession=result.getString("session_id"), trialId=activeCommand;
        if(restoreOnly){
            if(!journal.getBoolean("pending",false)||!address.equals(journal.getString("address",""))
                ||ownerSession.equals(journal.getString("session","")))
                throw new IllegalStateException("Restoration requires matching device receipt and a new ready session");
        }else if(journal.getBoolean("pending",false))throw new IllegalStateException("Restore pending setting first");
        LabAutoLockTrial.Port port=new LabAutoLockTrial.Port(){
            public void query(String id)throws Exception{
                beginSettingsQuery(id);
                sendBusiness("LAUNCHER",4,new JSONObject().put("cmd","request_general_settings")
                    .put("payload",new JSONObject().put("value",0).put("mode",0).put("data","")),"settings-query-"+id);
            }
            public void write(String id,int seconds)throws Exception{sendBusiness("LAUNCHER",5,LabAutoLockTrial.payload(seconds),id);}
            public boolean save(int original,int target){return journal.edit().putBoolean("pending",true)
                .putString("address",address).putString("session",ownerSession).putString("trial",trialId)
                .putInt("original",original).putInt("target",target).commit();}
            public boolean clear(){return journal.edit().clear().commit();}
        };
        autoLockTrial=restoreOnly?new LabAutoLockTrial(trialId,port,journal.getInt("original",-1),journal.getInt("target",-1))
            :observeAssistant?new LabAutoLockTrial(trialId,port,15,120):new LabAutoLockTrial(trialId,port);
        autoLockPublishedDone=false;autoLockPublishedPhase="";
        autoLockTrial.start(SystemClock.elapsedRealtime());publishAutoLockTrial();
    }
    private void advanceAutoLockTrial()throws Exception{
        if(autoLockTrial==null||autoLockTrial.done)return;
        LabSettingsQuery q=settingsQuery;
        if(q!=null&&q.done){
            JSONObject values=result.optJSONObject("reported_settings_values");
            long now=SystemClock.elapsedRealtime(), age=now-q.replyAt;
            autoLockTrial.read(q.id,q.passed()&&age>=0&&age<=10000,values==null?null:values.opt("generalSettings.autoLockTime"),now);
        }
        autoLockTrial.tick(SystemClock.elapsedRealtime());publishAutoLockTrial();
    }
    private void publishAutoLockTrial()throws Exception{
        LabAutoLockTrial t=autoLockTrial;if(t==null)return;
        result.put("crown_restore_pending",crownPending());
        result.put("head_restore_pending",headPending());
        result.put("wake_restore_pending",wakePending());
        result.put("auto_lock_restore_pending",autoLockPending());
        result.put("auto_lock_trial",new JSONObject().put("id",t.id).put("phase",t.phase).put("issue",t.issue)
            .put("original",t.original).put("target",t.target).put("baseline_read",t.baselineRead)
            .put("target_read",t.targetRead).put("restore_read",t.restoreRead)
            .put("target_send_completed",t.targetSent).put("restore_send_completed",t.restoreSent)
            .put("restored",t.restored).put("requires_new_session",t.ambiguous)
            .put("passed",t.passed()&&!autoLockPending()).put("lens_observed",false));
        if(t.done&&!autoLockPublishedDone){
            autoLockPublishedDone=true;
            if(t.id.equals(activeCommand))result.getJSONObject("last_command").put("status",t.passed()&&!autoLockPending()?"completed":"failed");
        }
        if(!t.phase.equals(autoLockPublishedPhase)){autoLockPublishedPhase=t.phase;stage("auto_lock_trial_state",t.phase);}
    }

    private static Object crownSnapshot(RayNeoCrownSettings value)throws Exception{return value==null?JSONObject.NULL:value.snapshot();}
    private static RayNeoCrownSettings readCrownJournal(SharedPreferences journal,String prefix)throws Exception{
        return RayNeoCrownSettings.read(new JSONObject().put("direction",journal.getInt(prefix+"direction",-1))
            .put("double",journal.getInt(prefix+"double",-1)).put("longPress",journal.getInt(prefix+"longPress",-1)),"");
    }
    private SharedPreferences crownJournal(){return getSharedPreferences("sdk_lab_crown",MODE_PRIVATE);}
    private boolean crownPending(){return getPackageName().equals("dev.xr.rayneo.sdklab")&&crownJournal().getBoolean("pending",false);}
    private void beginCrownTrial(boolean restoreOnly)throws Exception{
        if(!getPackageName().equals("dev.xr.rayneo.sdklab")||!connectionReady()||!persistentSession
            ||standbyEnabled||voiceArmed||voicePreparing||voiceRecording||recordingPreparing||(recorder!=null&&recorder.busy())
            ||headPending()||wakePending()||autoLockPending()||displayTrial!=null||answerTrial!=null||brightnessJournal().getBoolean("pending",false)
            ||(settingsQuery!=null&&!settingsQuery.done)||settingsQueryUncertain)
            throw new IllegalStateException("Requires idle persistent ready Lab with unambiguous queries");
        final SharedPreferences journal=crownJournal();
        final String ownerSession=result.getString("session_id"), trialId=activeCommand;
        if(restoreOnly){
            if(!journal.getBoolean("pending",false)||!address.equals(journal.getString("address",""))
                ||ownerSession.equals(journal.getString("session","")))
                throw new IllegalStateException("Restoration requires matching device receipt and a new ready session");
        }else if(journal.getBoolean("pending",false))throw new IllegalStateException("Restore pending setting first");
        LabCrownTrial.Port port=new LabCrownTrial.Port(){
            public void query(String id)throws Exception{
                beginSettingsQuery(id);
                sendBusiness("LAUNCHER",4,new JSONObject().put("cmd","request_general_settings")
                    .put("payload",new JSONObject().put("value",0).put("mode",0).put("data","")),"settings-query-"+id);
            }
            public void write(String id,RayNeoCrownSettings settings)throws Exception{sendBusiness("LAUNCHER",5,settings.payload(),id);}
            public boolean save(RayNeoCrownSettings original,RayNeoCrownSettings target){return journal.edit().putBoolean("pending",true)
                .putString("address",address).putString("session",ownerSession).putString("trial",trialId)
                .putInt("original.direction",original.direction).putInt("original.double",original.doubleTap).putInt("original.longPress",original.screenOffLongPress)
                .putInt("target.direction",target.direction).putInt("target.double",target.doubleTap).putInt("target.longPress",target.screenOffLongPress).commit();}
            public boolean clear(){return journal.edit().clear().commit();}
        };
        crownTrial=restoreOnly?new LabCrownTrial(trialId,port,readCrownJournal(journal,"original."),readCrownJournal(journal,"target."))
            :new LabCrownTrial(trialId,port);
        crownPublishedDone=false;crownPublishedPhase="";
        crownTrial.start(SystemClock.elapsedRealtime());publishCrownTrial();
    }
    private void advanceCrownTrial()throws Exception{
        if(crownTrial==null||crownTrial.done)return;
        LabSettingsQuery q=settingsQuery;
        if(q!=null&&q.done){
            JSONObject values=result.optJSONObject("reported_settings_values");
            long now=SystemClock.elapsedRealtime(), age=now-q.replyAt;
            crownTrial.read(q.id,q.passed()&&age>=0&&age<=10000,RayNeoCrownSettings.read(values,"generalSettings.crownConfig."),now);
        }
        crownTrial.tick(SystemClock.elapsedRealtime());publishCrownTrial();
    }
    private void publishCrownTrial()throws Exception{
        LabCrownTrial t=crownTrial;if(t==null)return;
        result.put("crown_restore_pending",crownPending());
        result.put("crown_trial",new JSONObject().put("id",t.id).put("phase",t.phase).put("issue",t.issue)
            .put("original",crownSnapshot(t.original)).put("target",crownSnapshot(t.target)).put("baseline_read",crownSnapshot(t.baselineRead))
            .put("target_read",crownSnapshot(t.targetRead)).put("restore_read",crownSnapshot(t.restoreRead))
            .put("target_send_completed",t.targetSent).put("restore_send_completed",t.restoreSent)
            .put("restored",t.restored).put("requires_new_session",t.ambiguous)
            .put("passed",t.passed()&&!crownPending()).put("lens_observed",false));
        if(t.done&&!crownPublishedDone){
            crownPublishedDone=true;
            if(t.id.equals(activeCommand))result.getJSONObject("last_command").put("status",t.passed()&&!crownPending()?"completed":"failed");
        }
        if(!t.phase.equals(crownPublishedPhase)){crownPublishedPhase=t.phase;stage("crown_trial_state",t.phase);}
    }

    private static Object headSnapshot(RayNeoHeadSettings value)throws Exception{return value==null?JSONObject.NULL:value.snapshot();}
    private static RayNeoHeadSettings readHeadJournal(SharedPreferences journal,String prefix)throws Exception{
        return RayNeoHeadSettings.read(new JSONObject().put("enabled",journal.getInt(prefix+"enabled",-1))
            .put("mode",journal.getInt(prefix+"mode",-1)),"");
    }
    private SharedPreferences headJournal(){return getSharedPreferences("sdk_lab_head",MODE_PRIVATE);}
    private boolean headPending(){return getPackageName().equals("dev.xr.rayneo.sdklab")&&headJournal().getBoolean("pending",false);}
    private void beginHeadTrial(boolean restoreOnly)throws Exception{
        if(!getPackageName().equals("dev.xr.rayneo.sdklab")||!connectionReady()||!persistentSession
            ||standbyEnabled||voiceArmed||voicePreparing||voiceRecording||recordingPreparing||(recorder!=null&&recorder.busy())
            ||wakePending()||autoLockPending()||crownPending()||displayTrial!=null||answerTrial!=null||brightnessJournal().getBoolean("pending",false)
            ||(settingsQuery!=null&&!settingsQuery.done)||settingsQueryUncertain)
            throw new IllegalStateException("Requires idle persistent ready Lab with unambiguous queries");
        final SharedPreferences journal=headJournal();
        final String ownerSession=result.getString("session_id"), trialId=activeCommand;
        if(restoreOnly){
            if(!journal.getBoolean("pending",false)||!address.equals(journal.getString("address",""))
                ||ownerSession.equals(journal.getString("session","")))
                throw new IllegalStateException("Restoration requires matching device receipt and a new ready session");
        }else if(journal.getBoolean("pending",false))throw new IllegalStateException("Restore pending setting first");
        LabHeadTrial.Port port=new LabHeadTrial.Port(){
            public void query(String id)throws Exception{
                beginSettingsQuery(id);
                sendBusiness("LAUNCHER",4,new JSONObject().put("cmd","request_general_settings")
                    .put("payload",new JSONObject().put("value",0).put("mode",0).put("data","")),"settings-query-"+id);
            }
            public void write(String id,RayNeoHeadSettings settings)throws Exception{sendBusiness("LAUNCHER",5,settings.payload(),id);}
            public boolean save(RayNeoHeadSettings original,RayNeoHeadSettings target){return journal.edit().putBoolean("pending",true)
                .putString("address",address).putString("session",ownerSession).putString("trial",trialId)
                .putInt("original.enabled",original.enabled).putInt("original.mode",original.mode)
                .putInt("target.enabled",target.enabled).putInt("target.mode",target.mode).commit();}
            public boolean clear(){return journal.edit().clear().commit();}
        };
        headTrial=restoreOnly?new LabHeadTrial(trialId,port,readHeadJournal(journal,"original."),readHeadJournal(journal,"target."))
            :new LabHeadTrial(trialId,port);
        headPublishedDone=false;headPublishedPhase="";
        headTrial.start(SystemClock.elapsedRealtime());publishHeadTrial();
    }
    private void advanceHeadTrial()throws Exception{
        if(headTrial==null||headTrial.done)return;
        LabSettingsQuery q=settingsQuery;
        if(q!=null&&q.done){
            JSONObject values=result.optJSONObject("reported_settings_values");
            long now=SystemClock.elapsedRealtime(), age=now-q.replyAt;
            headTrial.read(q.id,q.passed()&&age>=0&&age<=10000,RayNeoHeadSettings.read(values,"generalSettings.headGestures."),now);
        }
        headTrial.tick(SystemClock.elapsedRealtime());publishHeadTrial();
    }
    private void publishHeadTrial()throws Exception{
        LabHeadTrial t=headTrial;if(t==null)return;
        result.put("head_restore_pending",headPending());
        result.put("wake_restore_pending",wakePending());
        result.put("head_trial",new JSONObject().put("id",t.id).put("phase",t.phase).put("issue",t.issue)
            .put("original",headSnapshot(t.original)).put("target",headSnapshot(t.target)).put("baseline_read",headSnapshot(t.baselineRead))
            .put("target_read",headSnapshot(t.targetRead)).put("restore_read",headSnapshot(t.restoreRead))
            .put("target_send_completed",t.targetSent).put("restore_send_completed",t.restoreSent)
            .put("restored",t.restored).put("requires_new_session",t.ambiguous)
            .put("passed",t.passed()&&!headPending()).put("lens_observed",false).put("scope","configuration_only"));
        if(t.done&&!headPublishedDone){
            headPublishedDone=true;
            if(t.id.equals(activeCommand))result.getJSONObject("last_command").put("status",t.passed()&&!headPending()?"completed":"failed");
        }
        if(!t.phase.equals(headPublishedPhase)){headPublishedPhase=t.phase;stage("head_trial_state",t.phase);}
    }

    private static Object wakeSnapshot(RayNeoWakeSettings value)throws Exception{return value==null?JSONObject.NULL:value.snapshot();}
    private static RayNeoWakeSettings readWakeJournal(SharedPreferences journal,String prefix)throws Exception{
        return RayNeoWakeSettings.read(new JSONObject().put("headupSwitch",journal.getInt(prefix+"headupSwitch",-1))
            .put("headupDegree",journal.getInt(prefix+"headupDegree",-1)).put("crownSwitch",journal.getInt(prefix+"crownSwitch",-1)),"");
    }
    private SharedPreferences wakeJournal(){return getSharedPreferences("sdk_lab_wake",MODE_PRIVATE);}
    private boolean wakePending(){return getPackageName().equals("dev.xr.rayneo.sdklab")&&wakeJournal().getBoolean("pending",false);}
    private void beginWakeTrial(boolean restoreOnly,int comparisonDegrees)throws Exception{
        if(!getPackageName().equals("dev.xr.rayneo.sdklab")||!connectionReady()||!persistentSession
            ||standbyEnabled||voiceArmed||voicePreparing||voiceRecording||recordingPreparing||(recorder!=null&&recorder.busy())
            ||headPending()||autoLockPending()||crownPending()||displayTrial!=null||answerTrial!=null||brightnessJournal().getBoolean("pending",false)
            ||(settingsQuery!=null&&!settingsQuery.done)||settingsQueryUncertain)
            throw new IllegalStateException("Requires idle persistent ready Lab with unambiguous queries");
        final SharedPreferences journal=wakeJournal();
        final String ownerSession=result.getString("session_id"), trialId=activeCommand;
        if(restoreOnly){
            if(!journal.getBoolean("pending",false)||!address.equals(journal.getString("address",""))
                ||ownerSession.equals(journal.getString("session","")))
                throw new IllegalStateException("Restoration requires matching device receipt and a new ready session");
        }else if(journal.getBoolean("pending",false))throw new IllegalStateException("Restore pending setting first");
        LabWakeTrial.Port port=new LabWakeTrial.Port(){
            public void query(String id)throws Exception{
                beginSettingsQuery(id);
                sendBusiness("LAUNCHER",4,new JSONObject().put("cmd","request_general_settings")
                    .put("payload",new JSONObject().put("value",0).put("mode",0).put("data","")),"settings-query-"+id);
            }
            public void write(String id,RayNeoWakeSettings settings)throws Exception{sendBusiness("LAUNCHER",5,settings.payload(),id);}
            public boolean save(RayNeoWakeSettings original,RayNeoWakeSettings target){return journal.edit().putBoolean("pending",true)
                .putString("address",address).putString("session",ownerSession).putString("trial",trialId)
                .putInt("original.headupSwitch",original.headupSwitch).putInt("original.headupDegree",original.headupDegree).putInt("original.crownSwitch",original.crownSwitch)
                .putInt("target.headupSwitch",target.headupSwitch).putInt("target.headupDegree",target.headupDegree).putInt("target.crownSwitch",target.crownSwitch).commit();}
            public boolean clear(){return journal.edit().clear().commit();}
        };
        wakeTrial=restoreOnly?new LabWakeTrial(trialId,port,readWakeJournal(journal,"original."),readWakeJournal(journal,"target."))
            :comparisonDegrees<0?new LabWakeTrial(trialId,port):new LabWakeTrial(trialId,port,comparisonDegrees);
        wakePublishedDone=false;wakePublishedPhase="";
        wakeTrial.start(SystemClock.elapsedRealtime());publishWakeTrial();
    }
    private void advanceWakeTrial()throws Exception{
        if(wakeTrial==null||wakeTrial.done)return;
        LabSettingsQuery q=settingsQuery;
        if(q!=null&&q.done){
            JSONObject values=result.optJSONObject("reported_settings_values");
            long now=SystemClock.elapsedRealtime(), age=now-q.replyAt;
            wakeTrial.read(q.id,q.passed()&&age>=0&&age<=10000,RayNeoWakeSettings.read(values,"generalSettings.wakeupConfig."),now);
        }
        wakeTrial.tick(SystemClock.elapsedRealtime());publishWakeTrial();
    }
    private void publishWakeTrial()throws Exception{
        LabWakeTrial t=wakeTrial;if(t==null)return;
        result.put("wake_restore_pending",wakePending());
        result.put("wake_trial",new JSONObject().put("id",t.id).put("phase",t.phase).put("issue",t.issue)
            .put("original",wakeSnapshot(t.original)).put("target",wakeSnapshot(t.target)).put("baseline_read",wakeSnapshot(t.baselineRead))
            .put("target_read",wakeSnapshot(t.targetRead)).put("restore_read",wakeSnapshot(t.restoreRead))
            .put("target_send_completed",t.targetSent).put("restore_send_completed",t.restoreSent)
            .put("restored",t.restored).put("requires_new_session",t.ambiguous)
            .put("passed",t.passed()&&!wakePending()).put("lens_observed",false).put("scope","configuration_only"));
        if(t.done&&!wakePublishedDone){
            wakePublishedDone=true;
            if(t.id.equals(activeCommand))result.getJSONObject("last_command").put("status",t.passed()&&!wakePending()?"completed":"failed");
        }
        if(!t.phase.equals(wakePublishedPhase)){wakePublishedPhase=t.phase;stage("wake_trial_state",t.phase);}
    }

    private android.content.SharedPreferences brightnessJournal(){return getSharedPreferences("sdk_lab_brightness",MODE_PRIVATE);}
    private void beginBrightnessTrial(boolean restoreOnly,int target)throws Exception{
        if(!getPackageName().equals("dev.xr.rayneo.sdklab")||!authenticated||standbyEnabled||voiceArmed||voiceRecording||voicePreparing||recordingPreparing||(recorder!=null&&recorder.busy()))
            throw new IllegalStateException("Brightness trial requires idle SDK Lab");
        android.content.SharedPreferences journal=brightnessJournal();
        if(restoreOnly){
            if(!journal.getBoolean("pending",false)||!address.equals(journal.getString("address",""))||journal.getInt("original",-1)!=7)
                throw new IllegalStateException("No matching brightness restoration receipt");
        }else{
            if(journal.getBoolean("pending",false))throw new IllegalStateException("Resolve prior brightness restoration first");
            JSONObject state=result.optJSONObject("reported_status");
            if(state==null||!LabBrightnessTrial.eligible(state.opt("brightness"),state.opt("automaticBrightness"),System.currentTimeMillis()-result.optLong("reported_status_at_ms")))
                throw new IllegalStateException("Requires fresh manual brightness 7; no setting changed");
            if(!journal.edit().putBoolean("pending",true).putInt("original",7).putString("address",address).commit())
                throw new IllegalStateException("Cannot persist brightness restoration receipt");
        }
        brightnessTrial=new LabBrightnessTrial(activeCommand,restoreOnly,target);brightnessDonePublished=false;
        advanceBrightnessTrial();
    }
    private void brightnessWrite(int level,String id)throws Exception{
        sendBusiness("LAUNCHER",2,new JSONObject().put("cmd","brightness_change")
            .put("payload",new JSONObject().put("value",level).put("mode",0).put("data","")),id);
    }
    private void advanceBrightnessTrial()throws Exception{
        if(brightnessTrial==null||brightnessDonePublished)return;
        int action=brightnessTrial.tick(SystemClock.elapsedRealtime());
        if(action!=0){
            String request="brightness-"+action+"-"+brightnessTrial.id;
            try{
                if(action==LabBrightnessTrial.TARGET)brightnessWrite(brightnessTrial.target,request);
                else if(action==LabBrightnessTrial.RESTORE)brightnessWrite(7,request);
                else sendBusiness("LAUNCHER",1,new JSONObject().put("cmd","request_general_status")
                    .put("payload",new JSONObject().put("value",0).put("mode",0).put("data","")),request);
            }catch(Exception e){brightnessTrial.callback(action,false);}
        }
        if(action!=0||brightnessTrial.done)publishBrightnessTrial();
    }
    private void publishBrightnessTrial()throws Exception{
        if(brightnessTrial==null)return;
        LabBrightnessTrial t=brightnessTrial;
        if(t.done&&!brightnessDonePublished){
            brightnessDonePublished=true;
            if(t.restored&&!brightnessJournal().edit().clear().commit())t.phase="restored_journal_clear_failed";
            if(t.id.equals(activeCommand))result.getJSONObject("last_command").put("status",t.passed()&&!brightnessJournal().getBoolean("pending",false)?"completed":"failed");
        }
        result.put("brightness_restore_pending",brightnessJournal().getBoolean("pending",false));
        result.put("brightness_trial",new JSONObject().put("phase",t.phase).put("original",7).put("target",t.target).put("hold_ms",t.holdMs)
            .put("target_write_ack",t.targetAck).put("target_readback",t.targetObserved)
            .put("restore_write_ack",t.restoreAck).put("restore_readback",t.restored)
            .put("transport_error",t.transportError).put("passed",t.passed()&&!brightnessJournal().getBoolean("pending",false)).put("lens_observed",false));
        stage("brightness_trial_state",t.phase);
    }

    private void startRecordingTask() throws Exception {
        final String deviceId=pendingDeviceRecordingId;
        recordingPreparing=false;
        if(recorder!=null&&recorder.busy())throw new IOException("Recording already active");
        if(voiceRecording||voicePreparing)throw new IOException("Please finish the active voice question first");
        if(standbyEnabled||voiceArmed)stopStandby();
        if(recorder!=null)recorder.close();
        final GlassesRecorder[] owner=new GlassesRecorder[1];
        owner[0]=new GlassesRecorder(this,handler,new GlassesRecorder.Host(){
            public void send(int type,JSONObject body,String id)throws Exception{sendBusiness("RECORDING_SERVICE",type,body,id);}
            // Third copy of this default; CloudConfig.defaults() and CloudClient.validateConfig
            // hold the other two and must agree, or a validated import silently changes the cap.
            public int recordingCapMinutes(){
                try{return CloudConfig.load(SdkProbeActivity.this).optInt("recording_max_minutes",30);}
                catch(Exception unreadable){return 30;}
            }
            public void changed(JSONObject state){if(recorder!=owner[0])return;try{
                result.put("recording",state);
                publishVoiceRecording(state);
                String phase=state.optString("phase");
                // Standby can come back as soon as the recording lands: restoreStandbyIfNeeded()
                // guards on recorder.busy() and recordingPreparing, neither of which is still true
                // at "saved". Measured 2026-09-21: saved 86162ms, wake 87526ms, standby back
                // 87784ms — the user spoke inside the gap and the wake was dropped as standby_off.
                // The recording also stops needing the transport here, and since option 甲 that is
                // all that happens: the channel is not handed back. See submitTransportRelease.
                if(phase.equals("saved")||phase.equals("failed")){
                    endRecordingTransportUse("recording_end");
                    nextStandbyAttempt=0;
                    try{restoreStandbyIfNeeded();}catch(Exception ignored){}
                }
                if(!phase.equals(recordingNotificationPhase)){recordingNotificationPhase=phase;recordingTransportTrace("phase_"+phase);ConnectionService.recordingState(SdkProbeActivity.this,phase);}
                if(("record-start".equals(activeCommandKind)&&("recording".equals(phase)||"failed".equals(phase)))
                    ||("record-stop".equals(activeCommandKind)&&("saved".equals(phase)||"failed".equals(phase))))
                    result.getJSONObject("last_command").put("status","failed".equals(phase)?"failed":"completed");
                stage("recording_state",new JSONObject().put("phase",phase).put("bytes",state.optInt("bytes")));
            }catch(Exception ignored){}}
        });recorder=owner[0];
        if(deviceId==null)recorder.start();else recorder.acceptDeviceStart(deviceId);
        pendingDeviceRecordingId=null;
    }
    private AsrPartialGate partialGate = new AsrPartialGate(asrPartialPrefixStep, asrPartialIdleMs, asrPartialMaxPerRound);
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
        publishVoiceTiming(); // Refresh asynchronous receipt errors; do not invent new round events.
        result.put("connection_service_running", ConnectionService.running);
        if(Build.VERSION.SDK_INT>=31)result.put("companion_nearby",CompanionPresence.nearby)
            .put("companion_appearances",CompanionPresence.appearances).put("companion_presence_at",CompanionPresence.changedAt);
        if(routeObserver!=null&&!routeObserver.connectionFailure.isEmpty())
            result.put("connection_diagnostic",routeObserver.connectionFailure);
        if(routeObserver!=null)result.put("connection_initialization",routeObserver.diagnostics());
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
        if(asrTrial!=null&&!asrTrial.done){
            try{applyAsrActions(asrTrial,asrTrial.finish("session_ending",false,null,SystemClock.elapsedRealtime()));
                asrTrial.interrupted(SystemClock.elapsedRealtime());publishAsrTrial();}catch(Exception ignored){}
        }
        closeLabAsr();
        if(answerTrial!=null){
            try{
                if(answerTrial.stop(SystemClock.elapsedRealtime(),"session_ending")==LabAnswerTrial.EXIT)
                    sendBusiness("VOICE_ASSISTANT",7,new JSONObject().put("rc",1),"answer-cleanup-"+answerTrial.id);
                publishAnswerTrial();
            }catch(Exception ignored){}
        }
        if(brightnessTrial!=null&&!brightnessTrial.done){
            try{brightnessWrite(7,"brightness-cleanup-"+brightnessTrial.id);}catch(Exception ignored){}
            brightnessTrial.interrupt();try{publishBrightnessTrial();}catch(Exception ignored){}
        }
        if(autoLockTrial!=null&&!autoLockTrial.done){
            if(autoLockPending())try{sendBusiness("LAUNCHER",5,LabAutoLockTrial.payload(autoLockTrial.original),"auto-lock-cleanup-"+autoLockTrial.id);}catch(Exception ignored){}
            autoLockTrial.interrupt();try{publishAutoLockTrial();}catch(Exception ignored){}
        }
        if(crownTrial!=null&&!crownTrial.done){
            if(crownPending()&&crownTrial.original!=null)try{sendBusiness("LAUNCHER",5,crownTrial.original.payload(),"crown-cleanup-"+crownTrial.id);}catch(Exception ignored){}
            crownTrial.interrupt();try{publishCrownTrial();}catch(Exception ignored){}
        }
        if(wakeTrial!=null&&!wakeTrial.done){
            if(wakePending()&&wakeTrial.original!=null)try{sendBusiness("LAUNCHER",5,wakeTrial.original.payload(),"wake-cleanup-"+wakeTrial.id);}catch(Exception ignored){}
            wakeTrial.interrupt();try{publishWakeTrial();}catch(Exception ignored){}
        }
        if(headTrial!=null&&!headTrial.done){
            if(headPending()&&headTrial.original!=null)try{sendBusiness("LAUNCHER",5,headTrial.original.payload(),"head-cleanup-"+headTrial.id);}catch(Exception ignored){}
            headTrial.interrupt();try{publishHeadTrial();}catch(Exception ignored){}
        }
        archiveVoiceCancellation("failed".equals(status)?"session_failed":"session_ended");
        endVoiceRound(voiceCommand,"failed".equals(status)?"session_failed":"session_ended");
        if(voiceRounds!=null)voiceRounds.close();
        finishing = true;
        boolean clean = true;
        try {
            if (displayTrial != null && targetDevice != null
                    && displayTrial.stop(SystemClock.elapsedRealtime(), "session_ending") == LabDisplayTrial.EXIT)
                clean &= cleanupStep(() -> sendDisplayTrialExit());
            if (displayTrial != null) clean &= cleanupStep(this::publishDisplayTrial);
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
                if (connectionServiceStarted) ConnectionService.stop(this,this);
                connectionServiceStarted = false;
            });
            standbyEnabled = false; standbyReady = false; labNativeStandby=false;
            sendContinuations.clear();
            clean &= cleanupStep(() -> { if (cloudCancellation != null) cloudCancellation.cancel(); });
            clean &= cleanupStep(this::closeStreaming);
            cloudVoice = false; voicePreparing = false;
            OpusAudio.clear(voiceAudio); voiceWorker.shutdownNow();
            handler.removeCallbacksAndMessages(null);
            // Independent cleanup steps: a failed scanner stop must not skip BLE disconnect.
            clean &= cleanupStep(this::stopDiscovery);
            if (connectAttempted && targetDevice != null) {
                // Since option 甲 this is the only handback in the app, so it goes through the one
                // method that performs it rather than keeping a third verbatim copy of the sequence.
                if (sppRequested) clean &= cleanupStep(() -> {
                    recordingTransportTrace("session_cleanup_spp_attempt");
                    if (!submitTransportRelease("session_cleanup_spp"))
                        throw new IOException("SPP disconnect was not submitted");
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
            // Readiness belongs to the live session, never to its last successful snapshot.
            result.put("connection_ready", false);
            JSONObject endedStandby=result.optJSONObject("standby");
            if(endedStandby!=null)endedStandby.put("enabled",false).put("ready",false).put("phase","disconnected");
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
                    if (connectionServiceStarted) ConnectionService.stop(this,this);
                });
                connectionServiceStarted = false;
                finished = true;
                connectionActive = false;
                sessions.release(this);
                // Unplanned loss of a session that had authenticated: retry in the background unless
                // the user disconnected (auto_connect false). Stage 0.5, 09-23.
                // The host being torn down is not a link loss: no reconnect (review 09-23).
                if (persistentSession && authenticated && "failed".equals(status) && !HOST_DESTROYED.equals(reason))
                    cleanupStep(() -> AutoReconnect.afterDrop(this, sessionReadyAtMs));
                // After afterDrop: a pending reconnect keeps the host (and its location type) alive.
                cleanupStep(() -> ConnectionService.sessionEnded(this));
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
                result.put("last_spp_event_ms",SystemClock.elapsedRealtime()-startedAt);
                if (spp.equals("AUTH_SUCCESS") && args.length > 2 && args[2] == null) {
                    result.put("spp_auth_success_callback", true);
                    if ("spp".equals(activeCommandKind)) result.getJSONObject("last_command").put("status", "completed");
                } else if (spp.equals("DISCONNECT")) result.put("spp_auth_success_callback", false);
                recordingTransportTrace("spp_callback_"+spp);
            }
            if ("a".equals(method) && args[1] instanceof Enum) {
                String bond = ((Enum<?>)args[1]).name();
                result.put("last_bond_callback", bond);
                if (pairingWindowOpen && bond.equals("BONDED") && args.length > 2 && args[2] == null
                        && manager.getAdapter().getRemoteDevice(address).getBondState() == BluetoothDevice.BOND_BONDED) {
                    // Same persistence step as the original plugin's j.e bond callback.
                    runtime.type("I3.l").getMethod("a", runtime.type("I3.e0")).invoke(null, field(args[0], "b"));
                    result.put("sdk_bond_success", true);
                    pairingWindowOpen=false;
                    refreshBondState();
                    if ("pair".equals(activeCommandKind)) result.getJSONObject("last_command").put("status", "completed");
                    stage("system_pairing_completed", true);
                } else if (bond.equals("BONDED_FAIL")) {
                    pairingWindowOpen=false;bondEvidenceRequired=true;result.put("sdk_bond_success",false);
                    refreshBondState();
                    if("pair".equals(activeCommandKind))result.getJSONObject("last_command").put("status", "failed");
                    stage("system_pairing_failed", true);
                }
            }
            if ("e".equals(method) && args[1] instanceof Enum) {
                String state = ((Enum<?>) args[1]).name();
                result.put("last_ble_state", state);
                result.put("last_ble_event_ms",SystemClock.elapsedRealtime()-startedAt);
                recordingTransportTrace("ble_callback_"+state);
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
                    String error=args.length>2&&args[2] instanceof Enum?((Enum<?>)args[2]).name():"";
                    result.put("last_ble_error",error);
                    if(error.equals("DEVICE_IN_PAIRING_MODE"))result.put("connection_issue","device_pairing_mode");
                    complete("failed",error.equals("DEVICE_IN_PAIRING_MODE")?"眼镜正在配对模式；下一次连接将按配对模式处理，不清除原有文件或系统配对。":"SDK BLE state: " + state);
                } else if (connectAttempted && state.equals("DISCONNECTED")) {
                    String error=args.length>2&&args[2] instanceof Enum?((Enum<?>)args[2]).name():"";
                    result.put("last_ble_error",error);
                    if(error.equals("DEVICE_IN_PAIRING_MODE"))result.put("connection_issue","device_pairing_mode");
                    complete("failed", authenticated?"Disconnected during business observation":"Disconnected before authentication; connection attempt ended");
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
                boolean gattConnected = systemConnected();
                int expectedBond = (reconnectMode && !repairUnbonded) || labExistingSystemBond
                    ? BluetoothDevice.BOND_BONDED : BluetoothDevice.BOND_NONE;
                int vendorBond = bluetooth == null ? -1 : bluetooth.getBondState();
                stage("sdk_connect_preflight", new JSONObject().put("gatt_connected", gattConnected)
                    .put("vendor_device_present", bluetooth != null).put("vendor_bond_state", vendorBond)
                    .put("system_bond_state", manager.getAdapter().getRemoteDevice(address).getBondState())
                    .put("expected_bond_state", expectedBond));
                if (gattConnected || bluetooth == null || vendorBond != expectedBond) {
                    String issue = gattConnected ? "existing_gatt" : bluetooth == null ? "vendor_device_missing" : "bond_changed_before_connect";
                    result.put("connection_issue", issue);
                    complete("blocked_or_failed", "SDK connect preflight blocked: " + issue); return;
                }
                if (!"".equals(field(targetDevice, "g")) || !"".equals(field(targetDevice, "h")))
                    throw new IllegalStateException("Expected fresh guest device with empty account and bond key");
                // Original facade h.b(id, type, isPair) sets D.j before connecting.
                // A freshly reset device needs first pairing, not the default reconnect.
                runtime.type("I3.D").getField("j").setBoolean(targetDevice, !reconnectMode||pairingModeAttempt);
                stage(pairingModeAttempt?"sdk_saved_device_pairing_mode":reconnectMode ? "sdk_saved_device_reconnect" : "sdk_first_pairing", true);
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
        result.put("crown_restore_pending",crownPending());
        result.put("head_restore_pending",headPending());
        result.put("wake_restore_pending",wakePending());
        result.put("auto_lock_restore_pending",autoLockPending());
        stage("business_observation_started", true);
        if(getPackageName().equals("dev.xr.rayneo.sdklab"))result.put("brightness_restore_pending",brightnessJournal().getBoolean("pending",false));
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

    private void rememberCommand(String id) {
        // Preserve the existing bounded replay window; do not let refused commands grow it without limit.
        if (consumedCommands.size() >= 128) {
            consumedCommands.clear();
            if (activeCommand != null) consumedCommands.add(activeCommand);
        }
        consumedCommands.add(id);
    }

    private void rejectSessionCommand(JSONObject command, String reason) throws Exception {
        String requestedSession = command.optString("session_id");
        if (result.getString("session_id").equals(requestedSession)) rememberCommand(command.optString("command_id"));
        result.put("rejected_command", new JSONObject().put("id", command.optString("command_id"))
            .put("session_id", requestedSession).put("kind", command.optString("kind"))
            .put("status", "failed").put("reason", reason));
        // Keep last_command/activeCommand: callbacks and cancellation still belong to the accepted owner.
        // The kind belongs in the stage, not only in rejected_command: rejected_command holds one
        // entry and a second rejection overwrites the first, so a UI action that sends two commands
        // leaves a single "standby_busy" with no way to tell which one it was. Cost 2026-09-22 when
        // the settings page sent query + lab-settings-query together.
        stage("command_rejected", new JSONObject().put("reason", reason)
            .put("kind", command.optString("kind")).put("id", command.optString("command_id")));
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
                JSONObject previousCommand = result.optJSONObject("last_command");
                String rejection = SessionCommandGate.rejection(
                    result.getString("session_id").equals(command.optString("session_id")),
                    consumedCommands.contains(id) || (previousCommand != null && id.equals(previousCommand.optString("id"))),
                    !persistentSession && consumedCommands.size() >= 128,
                    previousCommand != null && "pending".equals(previousCommand.optString("status")),
                    displayTrial != null || answerTrial != null, standbyEnabled,
                    recordingPreparing || (recorder != null && recorder.busy()), connectionReady(), command.optString("kind"));
                if(rejection==null&&autoLockPending()&&!Arrays.asList("status","stop","lab-settings-query","lab-auto-lock-restore").contains(command.optString("kind")))
                    rejection="auto_lock_restore_pending";
                if(rejection==null&&crownPending()&&!Arrays.asList("status","stop","lab-settings-query","lab-crown-restore").contains(command.optString("kind")))
                    rejection="crown_restore_pending";
                if(rejection==null&&headPending()&&!Arrays.asList("status","stop","lab-settings-query","lab-head-restore").contains(command.optString("kind")))
                    rejection="head_restore_pending";
                if(rejection==null&&wakePending()&&!Arrays.asList("status","stop","lab-settings-query","lab-wake-restore").contains(command.optString("kind")))
                    rejection="wake_restore_pending";
                if(rejection==null&&asrTrial!=null&&!asrTrial.done&&!Arrays.asList("stop","standby-off").contains(command.optString("kind")))
                    rejection="asr_trial_busy";
                if (rejection != null) {
                    if ("duplicate_command".equals(rejection) && previousCommand != null && id.equals(previousCommand.optString("id"))
                            && !command.optString("kind").equals(previousCommand.optString("kind"))) {
                        rejectSessionCommand(command, "command_id_conflict");
                    } else if ("duplicate_command".equals(rejection) && previousCommand != null && id.equals(previousCommand.optString("id"))) {
                        // Echo retained state; never fail the original in-flight command on a duplicate submission.
                        result.put("duplicate_command", new JSONObject().put("id", id)
                            .put("session_id", command.optString("session_id")).put("kind", command.optString("kind"))
                            .put("status", previousCommand.optString("status")).put("executed_again", false)
                            .put("original_result", new JSONObject(previousCommand.toString())));
                        stage("command_duplicate", "Original command retained; no new execution");
                    } else {
                        rejectSessionCommand(command, "duplicate_command".equals(rejection) ? "duplicate_result_unavailable" : rejection);
                    }
                    if ("command_limit".equals(rejection)) {
                        complete("sdk_session_completed", "Session command limit reached"); return;
                    }
                } else {
                    rememberCommand(id);
                    activeCommand = id; activeCommandKind = command.getString("kind");
                    result.put("last_command", new JSONObject().put("id", id).put("kind", activeCommandKind).put("status", "pending").put("started_ms", SystemClock.elapsedRealtime()));
                    stage("session_command", activeCommandKind);
                    if(activeCommandKind.equals("lab-asr-trial")||activeCommandKind.equals("lab-asr-trial-1500")||activeCommandKind.equals("lab-asr-segments-8s")||activeCommandKind.equals("lab-asr-pause-trial")){
                        try{beginAsrTrial(id,activeCommandKind.equals("lab-asr-trial-1500")?1500:700,activeCommandKind.equals("lab-asr-segments-8s")?8000:0,activeCommandKind.equals("lab-asr-pause-trial")?2000:0);}catch(Exception e){result.getJSONObject("last_command").put("status","failed");stage("asr_trial_rejected",e.getMessage());}
                    } else if(Arrays.asList("lab-wake-trial","lab-wake-restore","lab-wake-low","lab-wake-high").contains(activeCommandKind)){
                        try{beginWakeTrial(activeCommandKind.equals("lab-wake-restore"),activeCommandKind.equals("lab-wake-low")?5:activeCommandKind.equals("lab-wake-high")?60:-1);}
                        catch(Exception e){result.getJSONObject("last_command").put("status","failed");stage("wake_trial_rejected",e.getMessage());}
                    } else if(activeCommandKind.equals("lab-head-trial")||activeCommandKind.equals("lab-head-restore")){
                        try{beginHeadTrial(activeCommandKind.equals("lab-head-restore"));}
                        catch(Exception e){result.getJSONObject("last_command").put("status","failed");stage("head_trial_rejected",e.getMessage());}
                    } else if(activeCommandKind.equals("lab-crown-trial")||activeCommandKind.equals("lab-crown-restore")){
                        try{beginCrownTrial(activeCommandKind.equals("lab-crown-restore"));}
                        catch(Exception e){result.getJSONObject("last_command").put("status","failed");stage("crown_trial_rejected",e.getMessage());}
                    } else if(activeCommandKind.equals("lab-auto-lock-trial")||activeCommandKind.equals("lab-auto-lock-restore")||activeCommandKind.equals("lab-auto-lock-observe")){
                        try{beginAutoLockTrial(activeCommandKind.equals("lab-auto-lock-restore"),activeCommandKind.equals("lab-auto-lock-observe"));}
                        catch(Exception e){result.getJSONObject("last_command").put("status","failed");stage("auto_lock_trial_rejected",e.getMessage());}
                    } else if(activeCommandKind.equals("apply-setting")){
                        try{applyGlassesSetting(command.optJSONObject("settings"),id);}
                        catch(Exception refused){
                            result.getJSONObject("last_command").put("status","failed").put("reason",String.valueOf(refused.getMessage()));
                            stage("setting_write_rejected",String.valueOf(refused.getMessage()));
                        }
                    } else if(activeCommandKind.equals("lab-settings-query")){
                        if(!getPackageName().equals("dev.xr.rayneo.sdklab")||!authenticated||standbyEnabled||voiceArmed||voiceRecording||voicePreparing||recordingPreparing||(recorder!=null&&recorder.busy())||(settingsQuery!=null&&!settingsQuery.done)||(dashboardQuery!=null&&!dashboardQuery.done)||(widgetCommand!=null&&!widgetCommand.done)||brightnessJournal().getBoolean("pending",false)){
                            result.getJSONObject("last_command").put("status","failed");stage("settings_query_rejected","Requires serial read-only query in idle Lab");
                        }else{
                            beginSettingsQuery(id);
                            sendBusiness("LAUNCHER",4,new JSONObject().put("cmd","request_general_settings").put("payload",new JSONObject().put("value",0).put("mode",0).put("data","")),"settings-query-"+id);
                        }
                    } else if(activeCommandKind.equals("lab-dashboard-query")){
                        // Serial read-only query, same isolation as the settings read -- including
                        // its two extra exclusions, so "serial" is enforced rather than only claimed.
                        if(!getPackageName().equals("dev.xr.rayneo.sdklab")||!authenticated||standbyEnabled||voiceArmed||voiceRecording||voicePreparing||recordingPreparing||(recorder!=null&&recorder.busy())||(settingsQuery!=null&&!settingsQuery.done)||(dashboardQuery!=null&&!dashboardQuery.done)||(widgetCommand!=null&&!widgetCommand.done)||brightnessJournal().getBoolean("pending",false)){
                            result.getJSONObject("last_command").put("status","failed");stage("dashboard_query_rejected","Requires serial read-only query in idle isolated Lab");
                        }else{
                            beginDashboardQuery(id);
                            sendBusiness("LAUNCHER",18,new JSONObject().put("cmd",LabDashboardQuery.CMD)
                                .put("payload",new JSONObject().put("version",1).put("value",0)),"dashboard-query-"+id);
                        }
                    } else if(activeCommandKind.equals("lab-widget-install")||activeCommandKind.equals("lab-widget-uninstall")){
                        // A2UI card write, pinned to our own card id. Same isolation as the baseline
                        // read, and serial with it: install decides against the last baseline.
                        boolean install=activeCommandKind.equals("lab-widget-install");
                        long age=lastBaselineAt<0?-1:SystemClock.elapsedRealtime()-lastBaselineAt;
                        boolean inBaseline=lastBaselineIds.contains(LabWidgetCommand.CARD_ID);
                        // Measured on the exact body that would go out, so the budget cannot drift from it.
                        int bodyBytes=widgetBody(install).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                        String refused=LabWidgetCommand.refusal(install,age,inBaseline,bodyBytes);
                        if(!getPackageName().equals("dev.xr.rayneo.sdklab")||!authenticated||standbyEnabled||voiceArmed||voiceRecording||voicePreparing||recordingPreparing||(recorder!=null&&recorder.busy())||(settingsQuery!=null&&!settingsQuery.done)||(dashboardQuery!=null&&!dashboardQuery.done)||(widgetCommand!=null&&!widgetCommand.done)||brightnessJournal().getBoolean("pending",false)){
                            result.getJSONObject("last_command").put("status","failed");stage("widget_command_rejected","Requires serial A2UI command in idle isolated Lab");
                        }else if(refused!=null){
                            result.getJSONObject("last_command").put("status","failed").put("reason",refused);stage("widget_command_rejected",refused);
                        }else{
                            widgetCommand=new LabWidgetCommand(id,install,SystemClock.elapsedRealtime());
                            widgetBodyBytes=bodyBytes;widgetCardInBaseline=lastBaselineAt<0?null:inBaseline;
                            result.remove("reported_widget_ack");result.remove("widget_candidate_frames");
                            publishWidgetCommand();
                            sendBusiness("LAUNCHER",18,widgetBody(install),"widget-"+id);
                        }
                    } else if(activeCommandKind.equals("lab-todo-full-sync")){
                        if(!getPackageName().equals("dev.xr.rayneo.sdklab")||!connectionReady()||standbyEnabled||voiceArmed||voiceRecording||voicePreparing||recordingPreparing||(recorder!=null&&recorder.busy())||todoBusy()){
                            result.getJSONObject("last_command").put("status","failed");stage("todo_full_sync_rejected","Requires idle connected Lab");
                        }else{
                            try{todoFullSync=new TodoFullSync(id,SystemClock.elapsedRealtime());advanceTodoFullSync();}
                            catch(Exception e){if(todoFullSync!=null&&todoFullSync.id.equals(id))failTodoFullSync(e);
                                else{result.getJSONObject("last_command").put("status","failed");stage("todo_full_sync_rejected",e.getClass().getSimpleName());}}
                        }
                    } else if(activeCommandKind.equals("lab-todo-sync")){
                        if(!getPackageName().equals("dev.xr.rayneo.sdklab")||!connectionReady()||standbyEnabled||voiceArmed||voiceRecording||voicePreparing||recordingPreparing||(recorder!=null&&recorder.busy())||todoBusy()){
                            result.getJSONObject("last_command").put("status","failed");stage("todo_sync_rejected","Requires idle connected Lab");
                        }else{
                            try{
                                JSONObject request=command.getJSONObject("todo");String mode=request.getString("operation");
                                if(mode.equals("rename"))todoSync=new LabTodoSync(id,mode,Long.parseLong(request.getString("event_id")),request.getString("title"),0,0,System.currentTimeMillis()/1000,SystemClock.elapsedRealtime());
                                else todoSync=localTodoSync(id,mode,request.getString("local_id"));
                                advanceTodoSync();
                            }catch(Exception e){if(todoSync!=null&&todoSync.id.equals(id)){todoSync.fail(e.getClass().getSimpleName());publishTodoSync();}
                                else{result.getJSONObject("last_command").put("status","failed");stage("todo_sync_rejected",e.getClass().getSimpleName());}}
                        }
                    } else if(activeCommandKind.equals("lab-todo-query")){
                        if(!getPackageName().equals("dev.xr.rayneo.sdklab")||!authenticated||standbyEnabled||voiceArmed||voiceRecording||voicePreparing||recordingPreparing||(recorder!=null&&recorder.busy())||todoBusy()){
                            result.getJSONObject("last_command").put("status","failed");stage("todo_query_rejected","One read-only query in idle Lab session");
                        }else{
                            todoQuery=new LabTodoQuery(id,SystemClock.elapsedRealtime());publishTodoQuery();
                            try{sendBusiness("SCHEDULE_TODO",15,LabTodoQuery.request(),"todo-query-"+id);}
                            catch(Exception e){todoQuery.fail("send_exception");publishTodoQuery();}
                        }
                    } else if(activeCommandKind.equals("lab-weather-update")){
                        try{
                            if(!getPackageName().equals("dev.xr.rayneo.sdklab")||!connectionReady()||standbyEnabled||voiceArmed||voiceRecording||voicePreparing||recordingPreparing||(recorder!=null&&recorder.busy()))throw new IllegalStateException("Requires idle connected Lab");
                            weatherUpdate=new LabWeatherUpdate(id,command.getJSONObject("weather"),System.currentTimeMillis(),SystemClock.elapsedRealtime());
                            publishWeatherUpdate();sendBusiness("LAUNCHER",18,weatherUpdate.payload,"weather-update-"+id);
                        }catch(Exception e){
                            if(weatherUpdate!=null&&weatherUpdate.id.equals(id)){weatherUpdate.done=true;weatherUpdate.issue="request_failed";publishWeatherUpdate();}
                            else{result.getJSONObject("last_command").put("status","failed");stage("weather_update_rejected",e.getClass().getSimpleName());}
                        }
                    } else if(activeCommandKind.equals("lab-firmware-query")){
                        if(!getPackageName().equals("dev.xr.rayneo.sdklab")||!authenticated||standbyEnabled||voiceArmed||voiceRecording||voicePreparing||recordingPreparing||(recorder!=null&&recorder.busy())||firmwareQuery!=null||brightnessJournal().getBoolean("pending",false)){
                            result.getJSONObject("last_command").put("status","failed");stage("firmware_query_rejected","One read-only query in idle isolated Lab session");
                        }else{
                            firmwareQuery=new LabFirmwareQuery(id,SystemClock.elapsedRealtime());publishFirmwareQuery();
                            sendBusiness("MARS_FOTA",1,new JSONObject(),"firmware-query-"+id);
                        }
                    } else if(activeCommandKind.equals("lab-answer-trial")){
                        if(!getPackageName().equals("dev.xr.rayneo.sdklab")||!authenticated||standbyEnabled||voiceArmed||voiceRecording||voicePreparing||recordingPreparing||(recorder!=null&&recorder.busy())||brightnessJournal().getBoolean("pending",false)||assistantPrefs().getBoolean("auto_standby",true)){
                            result.getJSONObject("last_command").put("status","failed");stage("answer_trial_rejected","Requires idle isolated Lab, auto standby off and no pending restore");
                        }else{
                            answerTrial=new LabAnswerTrial(id);
                            result.put("submitted_text",new JSONObject().put("title","固定问答显示测试").put("content","SDK 问答测试：中文 ABC 123。").put("uid",id));
                            advanceAnswerTrial(answerTrial.tick(SystemClock.elapsedRealtime()));
                            publishAnswerTrial();
                        }
                    } else if(activeCommandKind.equals("lab-brightness-trial")||activeCommandKind.equals("lab-brightness-restore")||activeCommandKind.equals("lab-brightness-min")||activeCommandKind.equals("lab-brightness-max")){
                        try{beginBrightnessTrial(activeCommandKind.equals("lab-brightness-restore"),activeCommandKind.equals("lab-brightness-min")?1:activeCommandKind.equals("lab-brightness-max")?17:8);}
                        catch(Exception e){result.getJSONObject("last_command").put("status","failed");stage("brightness_trial_blocked",e.getMessage());}
                    } else if (activeCommandKind.equals("lab-display-trial")) {
                        if (!getPackageName().equals("dev.xr.rayneo.sdklab") || standbyEnabled || voiceArmed || voiceRecording
                                || voicePreparing || recordingPreparing || (recorder != null && recorder.busy())) {
                            result.getJSONObject("last_command").put("status", "failed");
                            stage("display_trial_rejected", "Requires isolated Lab and idle ready session");
                        } else {
                            displayTrial = new LabDisplayTrial(id, SystemClock.elapsedRealtime());
                            JSONObject config = new JSONObject().put("font_size", 2).put("content_width", 100)
                                .put("max_lines", 5).put("position", "center").put("is_display", true).put("straight_view", "original");
                            publishDisplayTrial();
                            sendBusiness("AI_SUBTITLE", 7, new JSONObject().put("sid", id).put("force", false)
                                .put("scope", "temporary").put("config", config), "display-config-" + id);
                        }
                    } else if (activeCommandKind.equals("setup-finish")) {
                        if (!authenticated || voiceRecording || voicePreparing || (voiceArmed && !standbyReady) || (standbyEnabled && !standbyReady)) {
                            result.getJSONObject("last_command").put("status", "failed");
                            stage("setup_finish_blocked", "Requires authenticated idle session");
                        } else {
                            // Official PairSetupStackEnd -> setSetupMode(3): LAUNCHER 22,
                            // payload.value=0, payload.mode=3, data="". Manual diagnosis only;
                            // a transport callback is not confirmation that the lens menu opened.
                            result.put("setup_finish_sent", false);
                            JSONObject payload = new JSONObject().put("value", 0).put("mode", 3).put("data", "");
                            sendBusiness("LAUNCHER", 22, new JSONObject().put("cmd", "setup_control").put("payload", payload), id);
                        }
                    } else if (activeCommandKind.equals("record-start")) {
                        prepareRecordingTask();
                    } else if(activeCommandKind.equals("record-stop")) {
                        if(recordingPreparing)cancelRecordingPreparation(null);
                        else if(recorder==null||!recorder.busy()){result.getJSONObject("last_command").put("status","failed");stage("record_stop_no_task",true);}
                        else recorder.stop();
                    } else if (activeCommandKind.equals("stop")) {
                        result.getJSONObject("last_command").put("status", "completed");
                        complete("sdk_session_completed", "Stopped by user"); return;
                    } else if (activeCommandKind.equals("standby-off")) {
                        if(asrTrial!=null&&!asrTrial.done)applyAsrActions(asrTrial,asrTrial.finish("cancelled_by_host",false,null,SystemClock.elapsedRealtime()));
                        else {assistantPrefs().edit().putBoolean("auto_standby",false).apply();stopStandby();}
                        result.getJSONObject("last_command").put("status", "completed"); stage("standby_disabled", true);
                    } else if (activeCommandKind.equals("spp-off")) {
                        if (voiceRecording || voicePreparing || voiceArmed) throw new IllegalStateException("Stop voice test first");
                        if (recorder != null && recorder.busy()) throw new IllegalStateException("Stop the recording first");
                        // Option 甲 removed every automatic handback; this one is the operator
                        // asking for it by name, which is how the cost of releasing gets measured
                        // at all. It goes through the same single method so a fourth verbatim copy
                        // of the sequence cannot drift away from it.
                        recordingTransportTrace("explicit_spp_off_attempt");
                        if (!submitTransportRelease("explicit_spp_off"))
                            throw new IllegalStateException("SPP disconnect was not submitted");
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
                        if(labNativeMode())resetLabNativeRound();
                        if (cloudVoice) {
                            JSONObject cloudConfig = CloudConfig.load(this);
                            if (cloudConfig.optString("dashscope_key").isEmpty())
                                throw new IllegalStateException("Please configure the ASR key first");
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
                        requestSystemPairing(id);
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
            if(asrTrial!=null&&!asrTrial.done)applyAsrActions(asrTrial,asrTrial.tick(SystemClock.elapsedRealtime()));
            if(settingsQuery!=null&&!settingsQuery.done){settingsQuery.tick(SystemClock.elapsedRealtime());if(settingsQuery.done)publishSettingsQuery();}
            if(dashboardQuery!=null&&!dashboardQuery.done){dashboardQuery.tick(SystemClock.elapsedRealtime());if(dashboardQuery.done)publishDashboardQuery();}
            if(widgetCommand!=null&&!widgetCommand.done){widgetCommand.tick(SystemClock.elapsedRealtime());if(widgetCommand.done)publishWidgetCommand();}
            if(firmwareQuery!=null&&!firmwareQuery.done){firmwareQuery.tick(SystemClock.elapsedRealtime());publishFirmwareQuery();}
            if(weatherUpdate!=null&&!weatherUpdate.done){weatherUpdate.tick(SystemClock.elapsedRealtime());if(weatherUpdate.done)publishWeatherUpdate();}
            if(todoQuery!=null&&!todoQuery.done){todoQuery.tick(SystemClock.elapsedRealtime());publishTodoQuery();}
            if(todoSync!=null&&!todoSync.done){try{advanceTodoSync();}catch(Exception e){todoSync.fail(e.getClass().getSimpleName());publishTodoSync();}}
            if(todoFullSync!=null&&!todoFullSync.done){try{advanceTodoFullSync();}catch(Exception e){failTodoFullSync(e);}}
            if (displayTrial != null) advanceDisplayTrial(displayTrial.tick(SystemClock.elapsedRealtime()));
            if(answerTrial!=null)advanceAnswerTrial(answerTrial.tick(SystemClock.elapsedRealtime()));
            if(finished||finishing)return;
            advanceBrightnessTrial();
            advanceAutoLockTrial();
            advanceCrownTrial();
            advanceWakeTrial();
            advanceHeadTrial();
            restoreStandbyIfNeeded();
            PhoneNotifications.tick(this);
            syncPhoneClockIfNeeded();
            handler.postDelayed(this::sessionTick, standbyReady ? 1000 : 300);
        } catch (Throwable e) { complete("failed", rootError(e)); }
    }

    private String clockRequest="",clockBasis="";
    private JSONObject clockPayload;
    private long clockWallBase,clockElapsedBase,clockDeadline,clockAttemptAt=-60000;
    private boolean clockPending,clockRequestedByDevice;
    private void syncPhoneClockIfNeeded(){
        try{
            if(!getPackageName().equals("dev.xr.rayneo.sdklab")||finished||finishing||!connectionReady())return;
            long elapsed=SystemClock.elapsedRealtime(),wall=System.currentTimeMillis();java.util.TimeZone zone=java.util.TimeZone.getDefault();
            if(clockPending){if(elapsed>=clockDeadline){clockPending=false;clockBasis="";result.getJSONObject("phone_clock_sync").put("phase","send_ack_timeout");stage("phone_clock_state",result.getJSONObject("phone_clock_sync"));}return;}
            String basis=PhoneClock.basis(wall,elapsed,zone);
            boolean changed=clockBasis.isEmpty()||!basis.equals(clockBasis)||Math.abs((wall-clockWallBase)-(elapsed-clockElapsedBase))>2000;
            if((!changed&&!clockRequestedByDevice)||elapsed-clockAttemptAt<60000)return;
            JSONObject last=result.optJSONObject("last_command");
            if((last!=null&&"pending".equals(last.optString("status")))||voiceRecording||voicePreparing||recordingPreparing||(recorder!=null&&recorder.busy())||labNativeReadingActive()||autoLockPending()||crownPending()||headPending()||wakePending())return;
            clockRequest="phone-clock-"+java.util.UUID.randomUUID();clockPayload=PhoneClock.payload(wall,zone);clockPending=true;clockDeadline=elapsed+12000;clockAttemptAt=elapsed;
            clockBasis=basis;clockWallBase=wall;clockElapsedBase=elapsed;clockRequestedByDevice=false;
            result.put("phone_clock_sync",new JSONObject().put("request_id",clockRequest).put("payload",clockPayload).put("phase","pending").put("send_ack",false).put("lens_observed",false));
            try{sendBusiness("LAUNCHER",5,clockPayload,clockRequest);}catch(Exception e){clockPending=false;clockBasis="";result.getJSONObject("phone_clock_sync").put("phase","send_failed");}
            stage("phone_clock_state",result.getJSONObject("phone_clock_sync"));
        }catch(Exception e){android.util.Log.e("SdkProbe","phone_clock_sync_failed: "+e.getClass().getSimpleName());}
    }
    private static final String AUTO_SETTINGS_SYNC = "auto-settings-sync";
    private boolean autoSettingsSyncPending;
    private LabSettingsQuery settingsQuery;
    private LabDashboardQuery dashboardQuery;
    private LabWidgetCommand widgetCommand;
    private int widgetBodyBytes = -1;
    /** Whether our card id was in the last accepted baseline when this attempt started; null = no baseline. */
    private Boolean widgetCardInBaseline;
    /** Last dashboard baseline: when it landed and which card ids it listed. Install decides
     * against it and refuses when it is older than 120 s or already holds our id. */
    private long lastBaselineAt = -1;
    private final java.util.Set<String> lastBaselineIds = new java.util.HashSet<>();
    /** Pull one settings snapshot once the session is ready, so the settings page has values
     * without the user asking for them.
     *
     * <p>Deliberately NOT a session command: it goes straight out on the LAUNCHER channel and so
     * never meets {@link SessionCommandGate}. That matters because the gate refuses lab queries
     * while standby is on, and standby is on in the normal case -- routing this through a command
     * would mean the page is empty exactly when the user is most likely to open it. The same
     * reasoning is already in the codebase: {@code lab-auto-lock-observe} drops standbyEnabled and
     * voiceArmed from its gate for the same reason (the setting query uses a separate business channel).
     *
     * <p>Read-only: type 4 {@code request_general_settings} changes nothing on the glasses. The
     * reply lands in captureSettingsReply, which now keeps unsolicited snapshots too.
     *
     * <p>The delay lets the link settle after session_ready; ConnectionService uses the same 2s for
     * its own post-ready work. Failure is swallowed on purpose -- a convenience sync must never be
     * able to fail the session, which is exactly how a stray exception cost one earlier today. */
    /** Write one setting and keep it. Deliberately not a lab trial: those write, read back and
     * restore the original seconds later, which is right for an experiment and useless for a user
     * who wants the value to stay.
     *
     * <p>Only the gate conditions that still mean something for a product are kept. Standby and
     * the voice states are not among them -- settings ride the LAUNCHER channel while the assistant
     * uses VOICE_ASSISTANT, and the codebase already relies on that independence elsewhere
     * (lab-auto-lock-observe drops the same two conditions). Requiring the user to switch off
     * voice standby before changing brightness would be an artefact of the trials, not a real
     * constraint.
     *
     * <p>Sending is not the same as taking effect -- the rule this project keeps relearning -- so a
     * read-back is scheduled afterwards and the UI shows whatever comes back rather than assuming
     * the write landed. */
    private void applyGlassesSetting(JSONObject request,String id) throws Exception {
        if(!getPackageName().equals("dev.xr.rayneo.sdklab"))
            throw new GlassesSettingWrite.Refusal("当前应用不允许写入眼镜设置");
        if(!connectionReady())throw new GlassesSettingWrite.Refusal("眼镜连接未就绪，未写入");
        if(request==null)throw new GlassesSettingWrite.Refusal("缺少设置内容");
        String target=request.optString("target");
        JSONObject body=GlassesSettingWrite.body(target,request);
        int type=GlassesSettingWrite.type(target);
        sendBusiness("LAUNCHER",type,body,"setting-"+target+"-"+id);
        result.getJSONObject("last_command").put("status","completed").put("reason","sent_awaiting_readback");
        stage("setting_write_sent",new JSONObject().put("target",target).put("type",type));
        readBackAfterWrite(target);
    }
    /** Brightness answers on the status channel (type 1) and everything else on the settings
     * channel (type 4); the two must not be mixed, and type 4 replies
     * carry no brightness at all. Delayed so the glasses have applied the write first. */
    private void readBackAfterWrite(final String target){
        handler.postDelayed(() -> {
            try{
                if(finished||finishing||!connectionReady())return;
                boolean status="brightness".equals(target);
                sendBusiness("LAUNCHER",status?1:4,new JSONObject()
                    .put("cmd",status?"request_general_status":"request_general_settings")
                    .put("payload",new JSONObject().put("value",0).put("mode",0).put("data","")),
                    "setting-readback-"+target);
                stage("setting_readback_requested",target);
            }catch(Exception ignored){
                // A convenience read-back must never fail the session; the write already happened.
                try{ stage("setting_readback_failed",target); }catch(Exception ok){}
            }
        },800);
    }
    private void autoSyncSettings() {
        handler.postDelayed(() -> {
            try {
                if (finished || finishing || !connectionReady()) return;
                // Mutually exclusive with the lab queries. The reply carries no request id
                // (LabSettingsQuery.reply matches on LAUNCHER/type4/generalSettings only; observed traffic
                // records that no request-id echo has ever been demonstrated), so two identical
                // type 4 requests in flight produce replies nobody can tell apart -- a trial would
                // happily accept this one as its own evidence. Not sending is the cheap fix.
                if (settingsQuery != null && !settingsQuery.done) return;
                // Same for an A2UI card write on the same LAUNCHER channel.
                if (widgetCommand != null && !widgetCommand.done) return;
                autoSettingsSyncPending = true;
                sendBusiness("LAUNCHER", 4, new JSONObject().put("cmd", "request_general_settings")
                    .put("payload", new JSONObject().put("value", 0).put("mode", 0).put("data", "")),
                    AUTO_SETTINGS_SYNC);
                stage("settings_auto_sync_requested", true);
            } catch (Exception ignored) {
                autoSettingsSyncPending = false;
                // Convenience only; never fail the session for it. Recorded so it is not silent.
                try { stage("settings_auto_sync_failed", "request_not_sent"); } catch (Exception ok) {}
            }
        }, 2000);
    }
    private void inspectSettingsSnapshot(JSONObject object,String path,int depth,JSONObject values,JSONObject shapes) throws Exception {
        if(depth>4)return;
        for(Iterator<String> keys=object.keys();keys.hasNext()&&shapes.length()<120;){
            String key=keys.next();if(key.length()>80)continue;Object value=object.opt(key);String full=path.isEmpty()?key:path+"."+key;
            shapes.put(full,statusShape(value));
            if(ReportedSettingsPolicy.include(full,value))values.put(full,value);
            if(value instanceof JSONObject)inspectSettingsSnapshot((JSONObject)value,full,depth+1,values,shapes);
            else if(key.equals("data")&&value instanceof String&&((String)value).length()<8192){try{inspectSettingsSnapshot(new JSONObject((String)value),full,depth+1,values,shapes);}catch(Exception ignored){}}
        }
    }
    /** One read-only A2UI baseline read. Never installs or removes a card. */
    private void beginDashboardQuery(String id) throws Exception {
        dashboardQuery = new LabDashboardQuery(id, SystemClock.elapsedRealtime());
        result.remove("reported_dashboard");
        publishDashboardQuery();
    }

    private void publishDashboardQuery() throws Exception {
        LabDashboardQuery q = dashboardQuery;
        result.put("lab_dashboard_query", new JSONObject().put("id", q.id)
            .put("send_completed", q.sent).put("reply_received", q.reply)
            .put("done", q.done).put("issue", q.issue).put("read_only", true)
            .put("widgets_v2_count", q.widgets).put("passed", q.passed())
            .put("request", "{\"cmd\":\"dashboard_config\",\"payload\":{\"version\":1,\"value\":0}}")
            .put("association", "current device/session, LAUNCHER type18 window; no proven request ID echo")
            .put("meaning", "passed means the firmware answered with a widgets_v2 baseline; it does NOT mean any card was installed"));
        if (q.done && q.id.equals(activeCommand))
            result.getJSONObject("last_command").put("status", q.passed() ? "completed" : "failed");
    }

    private void publishWidgetCommand() throws Exception {
        LabWidgetCommand c = widgetCommand;
        result.put("lab_widget_command", new JSONObject().put("id", c.id)
            .put("action", c.install ? "install" : "uninstall").put("card_id", LabWidgetCommand.CARD_ID)
            .put("send_completed", c.sent).put("ack_received", c.reply).put("ack_code", c.reply ? c.code : JSONObject.NULL)
            .put("ack_err_msg", c.errMsg).put("done", c.done).put("issue", c.issue).put("passed", c.passed())
            .put("body_bytes", widgetBodyBytes).put("body_budget_bytes", LabWidgetCommand.MAX_BODY_BYTES)
            .put("card_in_last_baseline", widgetCardInBaseline == null ? JSONObject.NULL : widgetCardInBaseline)
            .put("association", "LAUNCHER type19 + numeric code inside this command's 12 s window; our envelope carries no protobuf sequence, so a late ack from an earlier attempt cannot be told apart")
            .put("meaning", "passed means the glasses acknowledged with code 0; whether the card is visible needs a baseline re-read and a human look"));
        if (c.done && c.id.equals(activeCommand))
            result.getJSONObject("last_command").put("status", c.passed() ? "completed" : "failed");
    }

    /** The card body, keys in the third-party project's sorted order. One Text component only --
     * the smallest thing that is visibly ours. */
    static JSONObject widgetBody(boolean install) throws Exception {
        String card = LabWidgetCommand.CARD_ID;
        if (!install) return new JSONObject().put("cmd", "widget_uninstall")
            .put("payload", new JSONObject().put("data", new JSONObject().put("id", card)));
        org.json.JSONArray components = new org.json.JSONArray().put(new JSONObject()
            .put("component", "Text").put("id", "root").put("text", LabWidgetCommand.CARD_TEXT).put("variant", "body"));
        JSONObject extras = new JSONObject().put("name", LabWidgetCommand.CARD_NAME)
            .put("uiContent", new JSONObject()
                .put("createSurface", new JSONObject().put("catalogId", LabWidgetCommand.CATALOG).put("surfaceId", card))
                .put("updateComponents", new JSONObject().put("components", components).put("surfaceId", card)))
            .put("widgetId", card);
        return new JSONObject().put("cmd", "widget_install").put("payload", new JSONObject().put("data", new JSONObject()
            .put("extras", extras.toString()).put("id", card).put("name", LabWidgetCommand.CARD_NAME).put("type", "a2ui")));
    }

    private void beginSettingsQuery(String id) throws Exception {
        // The pre-send exclusion in autoSyncSettings covers "trial already running"; this covers
        // the other order -- the sync went out first and its reply has not landed yet. Replies are
        // indistinguishable, so this trial may accept the sync's reply as its own. Cannot be
        // prevented without a request id, so it is at least made visible in the receipt.
        if(autoSettingsSyncPending)stage("settings_query_overlaps_auto_sync",
            new JSONObject().put("query",id).put("note","reply attribution ambiguous"));
        settingsQuery=new LabSettingsQuery(id,SystemClock.elapsedRealtime());
        result.remove("reported_settings_values");result.remove("reported_settings_shapes");result.remove("reported_settings_at_ms");
        publishSettingsQuery();
    }
    private void captureSettingsReply(String business,int type,JSONObject json) throws Exception {
        JSONObject values=new JSONObject(),shapes=new JSONObject();
        boolean snapshot="LAUNCHER".equals(business)&&type==4&&json.optJSONObject("generalSettings")!=null;
        if(snapshot)inspectSettingsSnapshot(json,"",0,values,shapes);
        // Any settings snapshot is worth keeping, not only one a trial asked for. Until 2026-09-22
        // this method returned immediately unless a lab query was in flight, so the snapshot that
        // arrives on its own -- or from the automatic sync at session_ready -- was decoded and
        // thrown away. That is why the settings page could only ever show data right after the
        // user pressed a button. The trial bookkeeping below still runs only for an active query.
        if(snapshot)autoSettingsSyncPending=false;
        if(snapshot&&values.length()>0&&(settingsQuery==null||settingsQuery.done)){
            result.put("reported_settings_values",values).put("reported_settings_shapes",shapes)
                .put("reported_settings_at_ms",System.currentTimeMillis());
            stage("settings_snapshot_captured",new JSONObject().put("value_count",values.length())
                .put("source","unsolicited"));
        }
        if(settingsQuery==null||settingsQuery.done)return;
        boolean accepted=settingsQuery.reply(business,type,json.optJSONObject("generalSettings")!=null,values.length(),SystemClock.elapsedRealtime());
        if(accepted){
            result.put("reported_settings_values",values).put("reported_settings_shapes",shapes)
                .put("reported_settings_at_ms",System.currentTimeMillis());
            stage("settings_query_reply",new JSONObject().put("command",json.optString("cmd"))
                .put("value_count",values.length()).put("shape_count",shapes.length()));
        }
        publishSettingsQuery();
    }
    private void publishSettingsQuery() throws Exception {
        LabSettingsQuery q=settingsQuery;
        result.put("lab_settings_query",new JSONObject().put("id",q.id)
            .put("send_completed",q.sent).put("send_ack",q.sent).put("reply_received",q.reply)
            .put("done",q.done).put("issue",q.issue).put("read_only",true).put("request_count",1)
            .put("value_count",q.values).put("ignored_replies",q.ignored).put("passed",q.passed())
            .put("send_ack_semantics","legacy alias of send_completed; not firmware apply ACK")
            .put("association","current device/session, serial type4 generalSettings window; no proven request ID echo"));
        if(q.done&&!q.passed())settingsQueryUncertain=true;
        if(q.done&&q.id.equals(activeCommand))result.getJSONObject("last_command").put("status",q.passed()?"completed":"failed");
    }
    private LabTodoSync todoSync;
    private VoiceTodoSyncRound voiceTodoSync;
    /** Mapping keys use the upper-case address: the CLI and the picker spell it differently (review 09-23). */
    private String todoAddress()throws Exception{return result.getString("target_address").toUpperCase(Locale.ROOT);}
    private String todoMapping(String localId)throws Exception{
        return getSharedPreferences("todo-glasses-links",MODE_PRIVATE).getString(todoAddress()+"/"+localId,null);
    }
    private LabTodoSync localTodoSync(String id,String mode,String localId)throws Exception{
        if(!mode.equals("push_local")&&!mode.equals("update_local"))throw new IllegalArgumentException("Unsupported todo operation");
        TodoStore.Item item;try(TodoStore store=new TodoStore(this)){item=store.find(localId);}
        String existing=todoMapping(localId);long eventId;
        if(existing==null&&mode.equals("update_local"))throw new IllegalArgumentException("Todo has no glasses identity");
        if(existing==null){eventId=new java.security.SecureRandom().nextLong()&Long.MAX_VALUE;if(eventId==0)eventId=1;
            if(!getSharedPreferences("todo-glasses-links",MODE_PRIVATE).edit().putString(todoAddress()+"/"+localId,Long.toString(eventId)).commit())throw new IOException("Todo mapping not saved");
        }else eventId=Long.parseLong(existing);
        return new LabTodoSync(id,mode,eventId,item.title,item.createdAtMs,item.completed()?1:0,System.currentTimeMillis()/1000,SystemClock.elapsedRealtime());
    }
    private boolean voiceOwnsTodoSync(){return voiceTodoSync!=null&&voiceTodoSync.sync==todoSync;}
    private TodoFullSync todoFullSync;
    private boolean todoBusy(){return (todoSync!=null&&!todoSync.done)||(todoQuery!=null&&!todoQuery.done)||(todoFullSync!=null&&!todoFullSync.done);}
    private void advanceTodoFullSync()throws Exception{
        TodoFullSync sync=todoFullSync;if(sync==null)return;
        if(!sync.id.equals(activeCommand)){sync.fail("owner_changed");publishTodoFullSync();return;}
        sync.advance(this::reconcileTodos,SystemClock.elapsedRealtime());publishTodoFullSync();if(sync.done)return;
        if((sync.phase.equals("reading_before")||sync.phase.equals("reading_after"))&&!sync.readSent){
            sync.readSent=true;sendBusiness("SCHEDULE_TODO",15,LabTodoQuery.request(),sync.readId());
        }else if(sync.phase.equals("writing_table")&&!sync.tableSent){
            // The glasses list before and the exact table are durable before the write.
            sync.tableSent=true;publishTodoFullSync();sendBusiness("SCHEDULE_TODO",6,sync.table,sync.tableId());
        }else if(sync.phase.equals("writing_delete")&&!sync.deleteSent){
            sync.deleteSent=true;publishTodoFullSync();sendBusiness("SCHEDULE_TODO",7,sync.deleteCommand,sync.deleteId());
        }
    }
    /** Fails the round and settles its command even when the receipt cannot be written (review
     * 09-23: an exception from the publish inside a catch ended the session with the command pending). */
    private void failTodoFullSync(Exception cause){
        TodoFullSync sync=todoFullSync;if(sync==null)return;
        sync.fail(cause.getClass().getSimpleName());
        try{publishTodoFullSync();}
        catch(Exception receipt){
            try{
                result.put("lab_todo_full_sync",new JSONObject().put("command_id",sync.id).put("phase",sync.phase).put("issue",sync.issue+"+receipt_failed").put("verified",false));
                if(sync.id.equals(activeCommand))result.getJSONObject("last_command").put("status","failed");
            }catch(Exception ignored){}
        }
    }
    private void publishTodoFullSync()throws Exception{
        TodoFullSync sync=todoFullSync;if(sync==null)return;
        JSONObject receipt=sync.receipt().put("session_id",result.getString("session_id")).put("target_address",result.getString("target_address"));
        File dir=new File(getFilesDir(),"todo-sync");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Todo receipt directory");
        File temp=new File(dir,sync.id+".tmp"),target=new File(dir,sync.id+".json");
        try(FileOutputStream out=new FileOutputStream(temp)){out.write(receipt.toString(2).getBytes("UTF-8"));out.getFD().sync();}
        if(!temp.renameTo(target))throw new IOException("Todo receipt replacement");
        result.put("lab_todo_full_sync",new JSONObject().put("command_id",sync.id).put("phase",sync.phase).put("issue",sync.issue)
            .put("table_send_completed",sync.tableAck).put("verified",sync.passed()).put("items",sync.plan==null?-1:sync.plan.items.size())
            .put("adopted",sync.plan==null?0:sync.plan.adopted).put("completed_on_glasses",sync.plan==null?0:sync.plan.completedOnGlasses)
            .put("deleted",sync.plan==null?0:sync.plan.deleted.size()).put("receipt_path","todo-sync/"+sync.id+".json"));
        if(sync.passed()&&sync.plan!=null&&!sync.plan.deleted.isEmpty()&&!sync.deletesCleared){
            // The table no longer has them and the delete went out: forget them.
            SharedPreferences meta=getSharedPreferences("todo-glasses-meta",MODE_PRIVATE);String key=todoAddress()+"/deleted";
            Set<String> left=new HashSet<>(meta.getStringSet(key,new HashSet<>()));for(long gone:sync.plan.deleted)left.remove(Long.toString(gone));
            meta.edit().putStringSet(key,left).apply();sync.deletesCleared=true;
        }
        if(sync.done&&sync.id.equals(activeCommand))result.getJSONObject("last_command").put("status",sync.passed()?"completed":"failed");
    }
    /** The phone is the only source (09-23 official model). Before its pending list overwrites the
     * glasses: glasses items the phone does not know are adopted (the first sync loses nothing),
     * items completed on the glasses are completed on the phone, and phone deletions are collected
     * for the delete command. Content always comes from the phone (plan 1.8, option 甲). */
    private TodoFullSync.Plan reconcileTodos(LinkedHashMap<Long,JSONObject> glasses)throws Exception{
        String address=todoAddress();
        SharedPreferences links=getSharedPreferences("todo-glasses-links",MODE_PRIVATE),meta=getSharedPreferences("todo-glasses-meta",MODE_PRIVATE);
        Map<Long,String> localOf=new HashMap<>();
        for(Map.Entry<String,?> e:links.getAll().entrySet())
            if(e.getKey().startsWith(address+"/")&&e.getValue() instanceof String)try{localOf.put(Long.parseLong((String)e.getValue()),e.getKey().substring(address.length()+1));}catch(NumberFormatException ignored){}
        Set<String> deleted=new HashSet<>(meta.getStringSet(address+"/deleted",new HashSet<>()));
        int adopted=0,completed=0;List<JSONObject> items=new ArrayList<>();Set<Long> completedHere=new HashSet<>();
        SharedPreferences.Editor linkEdit=links.edit(),metaEdit=meta.edit();
        try(TodoStore store=new TodoStore(this)){
            for(JSONObject g:glasses.values()){
                long eventId=LabTodoSync.integer(g,"eventID");
                if(deleted.contains(Long.toString(eventId)))continue;
                String local=localOf.get(eventId);TodoStore.Item item=null;
                if(local!=null)try{item=store.find(local);}catch(IllegalArgumentException gone){deleted.add(Long.toString(eventId));continue;}
                if(item==null){
                    item=store.adopt(eventId,g.getString("title"),LabTodoSync.integer(g,"createTime"),LabTodoSync.integer(g,"status")==1);
                    linkEdit.putString(address+"/"+item.id,Long.toString(eventId));localOf.put(eventId,item.id);adopted++;
                    if(Boolean.TRUE.equals(g.opt("isImportant")))metaEdit.putBoolean(address+"/important/"+eventId,true);
                }
                // Also for a row adopt() found already there without a link (review 09-23).
                if(LabTodoSync.integer(g,"status")==1&&!item.completed()){store.setCompleted(item.id,true);completed++;}
            }
            if(!linkEdit.commit())throw new IOException("Todo mapping not saved");
            metaEdit.commit();
            Map<String,Long> eventOf=new HashMap<>();for(Map.Entry<Long,String> e:localOf.entrySet())eventOf.put(e.getValue(),e.getKey());
            List<JSONObject> important=new ArrayList<>(),normal=new ArrayList<>();
            SharedPreferences.Editor assign=links.edit();boolean assigned=false;
            for(TodoStore.Item item:store.list()){
                if(item.completed()){Long done=eventOf.get(item.id);if(done!=null)completedHere.add(done);continue;}
                Long eventId=eventOf.get(item.id);
                if(eventId==null){long fresh=new java.security.SecureRandom().nextLong()&Long.MAX_VALUE;if(fresh==0)fresh=1;eventId=fresh;assign.putString(address+"/"+item.id,Long.toString(fresh));assigned=true;}
                boolean flag=meta.getBoolean(address+"/important/"+eventId,false);
                (flag?important:normal).add(TodoFullSync.item(eventId,item.title,flag,item.createdAtMs,item.updatedAtMs/1000));
            }
            if(assigned&&!assign.commit())throw new IOException("Todo mapping not saved");
            items.addAll(important);items.addAll(normal);
        }
        List<Long> gone=new ArrayList<>();for(String value:deleted)try{gone.add(Long.parseLong(value));}catch(NumberFormatException ignored){}
        meta.edit().putStringSet(address+"/deleted",deleted).commit();
        return new TodoFullSync.Plan(items,gone,adopted,completed,completedHere);
    }
    /** SCHEDULE_TODO type 4 from the glasses: {"eventType":1,"eventID":…,"status":1,"lastModifiedTime":…}.
     * The phone list takes it; a full sync afterwards drops the item from the glasses list, as the
     * official app does (it pushed the table right after). */
    private void glassesCompletedTodo(JSONObject body)throws Exception{
        long eventId=LabTodoSync.integer(body,"eventID");int status=body.optInt("status",-1);
        String address=todoAddress(),local=null;
        // The glasses can only complete (09-23 capture); status 0 is the phone's direction, and
        // another eventType is not a todo. Neither changes the phone list (review 09-23).
        if(body.optInt("eventType",-1)!=1||status!=1){stage("todo_glasses_change_ignored",new JSONObject().put("event_id",Long.toString(eventId)).put("status",status).put("event_type",body.optInt("eventType",-1)));return;}
        for(Map.Entry<String,?> e:getSharedPreferences("todo-glasses-links",MODE_PRIVATE).getAll().entrySet())
            if(e.getKey().startsWith(address+"/")&&Long.toString(eventId).equals(e.getValue())){local=e.getKey().substring(address.length()+1);break;}
        JSONObject note=new JSONObject().put("event_id",Long.toString(eventId)).put("status",status).put("mapped",local!=null).put("at_ms",System.currentTimeMillis());
        if(local!=null){
            try(TodoStore store=new TodoStore(this)){store.setCompleted(local,true);note.put("applied",true);}
            catch(IllegalArgumentException gone){note.put("applied",false).put("reason","local_missing");}
        }
        result.put("todo_glasses_change",note);stage("todo_glasses_change",note);
        ConnectionService.requestTodoSync(this,2000);
    }
    private void advanceTodoSync()throws Exception{
        LabTodoSync sync=todoSync;if(sync==null)return;
        if(voiceOwnsTodoSync()&&!voiceTodoSync.current(result.optString("session_id"),activeCommand,currentRound(voiceTodoSync.command))){
            voiceTodoSync.cancel("voice_owner_changed");publishTodoSync();return;
        }
        if(!sync.id.equals(activeCommand)){sync.fail("owner_changed");publishTodoSync();return;}
        sync.advance(SystemClock.elapsedRealtime());publishTodoSync();if(sync.done)return;
        if((sync.phase.equals("reading_before")||sync.phase.equals("reading_after"))&&!sync.readSent){
            sync.readSent=true;sendBusiness("SCHEDULE_TODO",15,LabTodoQuery.request(),sync.readId());
        }else if(sync.phase.equals("writing")&&!sync.writeSent){
            // Full original list and exact one-item payload are durable before the write.
            sync.writeSent=true;publishTodoSync();sendBusiness("SCHEDULE_TODO",2,sync.payload,sync.writeId());
        }
    }
    private void publishTodoSync()throws Exception{
        LabTodoSync sync=todoSync;if(sync==null)return;
        JSONObject receipt=sync.receipt().put("session_id",result.getString("session_id")).put("target_address",result.getString("target_address"));
        File dir=new File(getFilesDir(),"todo-sync");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Todo receipt directory");
        File temp=new File(dir,sync.id+".tmp"),target=new File(dir,sync.id+".json");
        try(FileOutputStream out=new FileOutputStream(temp)){out.write(receipt.toString(2).getBytes("UTF-8"));out.getFD().sync();}
        if(!temp.renameTo(target))throw new IOException("Todo receipt replacement");
        result.put("lab_todo_sync",new JSONObject().put("command_id",sync.id).put("phase",sync.phase).put("issue",sync.issue)
            .put("write_send_completed",sync.writeAck).put("verified",sync.passed()).put("receipt_path","todo-sync/"+sync.id+".json"));
        if(voiceOwnsTodoSync()){
            if(sync.done){
                VoiceTodoSyncRound round=voiceTodoSync;
                if(!round.handled){
                    boolean display=round.finish(result.optString("session_id"),activeCommand,currentRound(round.command));
                    writeVoiceTodoReceipt(round.pipeline);
                    if(display)deliverVoiceTodo(round.pipeline,round.command);
                }
            }
        }else if(sync.done&&sync.id.equals(activeCommand))result.getJSONObject("last_command").put("status",sync.passed()?"completed":"failed");
    }
    private void cancelVoiceTodoSync(String command,String reason){
        VoiceTodoSyncRound round=voiceTodoSync;
        if(round==null||round.handled||!round.command.equals(command))return;
        round.cancel(reason);
        try{publishTodoSync();}catch(Exception e){android.util.Log.e("SdkProbe","voice_todo_cancel_receipt_failed: "+e.getClass().getSimpleName());}
    }
    private LabTodoQuery todoQuery;
    private void publishTodoQuery()throws Exception{
        LabTodoQuery q=todoQuery;
        if(q.done&&!q.stored){
            try{
                File dir=new File(getFilesDir(),"lab-todo");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("mkdir failed");
                File target=new File(dir,q.id+".json");
                JSONObject receipt=q.summary().put("session_id",result.getString("session_id")).put("target_address",result.getString("target_address")).put("request",LabTodoQuery.request()).put("replies",q.replies);
                try(FileOutputStream out=new FileOutputStream(target)){out.write(receipt.toString().getBytes("UTF-8"));out.getFD().sync();}
                q.stored=true;
            }catch(Exception e){q.issue="receipt_write_failed";q.stored=true;}
        }
        JSONObject summary=q.summary().put("receipt_path","lab-todo/"+q.id+".json");result.put("lab_todo_query",summary);
        if(q.done&&q.id.equals(activeCommand))result.getJSONObject("last_command").put("status",q.passed()?"completed":"failed");
        stage("todo_query_state",summary);
    }
    private LabWeatherUpdate weatherUpdate;
    private void publishWeatherUpdate()throws Exception{
        LabWeatherUpdate update=weatherUpdate;
        JSONObject receipt=update.receipt().put("session_id",result.getString("session_id")).put("target_address",result.getString("target_address"));
        String path="weather/"+update.id+".json";
        if(update.done){
            try{
                File dir=new File(getFilesDir(),"weather");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("mkdir failed");
                try(FileOutputStream out=new FileOutputStream(new File(getFilesDir(),path))){out.write(receipt.toString().getBytes("UTF-8"));out.getFD().sync();}
            }catch(Exception e){update.issue="receipt_write_failed";}
        }
        result.put("weather_update",new JSONObject().put("command_id",update.id).put("phase",update.done?(update.issue.isEmpty()?"transport_sent":"failed"):"pending")
            .put("send_ack",update.ack).put("issue",update.issue).put("receipt_path",path).put("readback_verified",false).put("lens_observed",false));
        if(update.done&&update.id.equals(activeCommand))result.getJSONObject("last_command").put("status",update.ack&&update.issue.isEmpty()?"completed":"failed");
        stage("weather_update_state",result.getJSONObject("weather_update"));
    }
    private LabFirmwareQuery firmwareQuery;
    private void publishFirmwareQuery() throws Exception {
        LabFirmwareQuery q=firmwareQuery;
        result.put("lab_firmware_query",new JSONObject().put("phase",q.phase).put("send_ack",q.ack)
            .put("version",q.version).put("issue",q.issue).put("replies",q.replies).put("ignored",q.ignored)
            .put("passed",q.passed()).put("read_only",true).put("request_count",1)
            .put("association","current device/session and time window; protocol request ID not observed"));
        if(q.done&&q.id.equals(activeCommand))result.getJSONObject("last_command").put("status",q.passed()?"completed":"failed");
    }

    private void publishAnswerTrial() throws Exception {
        LabAnswerTrial t=answerTrial;
        result.put("lab_answer_trial",new JSONObject().put("id",t.id).put("phase",t.phase).put("issue",t.issue)
            .put("question_send_ack",t.questionAck).put("answer_send_ack",t.answerAck).put("finish_send_ack",t.finishAck)
            .put("exit_send_ack",t.exitAck).put("device_exit_event",t.deviceExit).put("transport_complete",t.transportComplete())
            .put("wake_received",t.wakeReceived).put("ignored_pre_wake_exits",t.ignoredPreWakeExits)
            .put("audio_capture_requested",false).put("cloud_called",false).put("lens_observed",false));
        stage("answer_trial_state",t.phase);
    }
    private void advanceAnswerTrial(int action) throws Exception {
        if(answerTrial==null||finished||finishing)return;
        LabAnswerTrial t=answerTrial;
        if(action==LabAnswerTrial.FINISHED){
            if(t.owns(activeCommand))result.getJSONObject("last_command").put("status",t.transportComplete()?"completed":"failed");
            publishAnswerTrial();
            if(t.wakeReceived&&!t.exitAck){complete("failed","Fixed answer exit unconfirmed");return;}
            answerTrial=null;stage("answer_trial_finished_connection_retained",true);return;
        }
        if(action!=LabAnswerTrial.NONE){
            String request="answer-trial-"+action+"-"+t.id;
            try{
                if(action==LabAnswerTrial.QUESTION)sendBusiness("VOICE_ASSISTANT",5,new JSONObject().put("text","离线显示测试").put("final",true),request);
                else if(action==LabAnswerTrial.ANSWER)sendBusiness("VOICE_ASSISTANT",32,nativeAnswerPayload("SDK 问答测试：中文 ABC 123。","离线显示测试",t.id),request);
                else if(action==LabAnswerTrial.FINISH)sendBusiness("VOICE_ASSISTANT",12,new JSONObject(),request);
                else if(action==LabAnswerTrial.EXIT)sendBusiness("VOICE_ASSISTANT",7,new JSONObject().put("rc",1),request);
            }catch(Exception e){advanceAnswerTrial(t.sent(action,false,SystemClock.elapsedRealtime()));}
            if(!finished&&!finishing)publishAnswerTrial();
        }
    }
    private JSONObject nativeAnswerPayload(String text,String query,String command)throws Exception{
        return new JSONObject().put("sub","workflow").put("vendor","deepseek").put("uuid",command).put("sid",command)
            .put("round",-1).put("timestamp",System.currentTimeMillis()).put("query",query).put("domain","chat").put("intent","chat")
            .put("payload",new JSONObject()).put("offline",false).put("answer",new JSONObject().put("text",text).put("isFinal",false));
    }

    private void publishDisplayTrial() throws Exception {
        result.put("lab_display_trial", new JSONObject().put("id", displayTrial.id).put("phase", displayTrial.phase)
            .put("issue", displayTrial.issue).put("config_accepted", displayTrial.configAccepted)
            .put("text_send_completed", displayTrial.textSent).put("exit_send_completed", displayTrial.exitSent)
            .put("lens_verified", false).put("exit_verified", false).put("audio_requested", false).put("cloud_enabled", false));
        stage("lab_display_trial", result.getJSONObject("lab_display_trial"));
    }
    private void sendDisplayTrialExit() throws Exception {
        sendBusiness("AI_SUBTITLE", 3, new JSONObject().put("sid", displayTrial.id).put("reason_code", 10).put("text", ""),
            "display-exit-" + displayTrial.id);
    }
    private void advanceDisplayTrial(int action) throws Exception {
        if (action == LabDisplayTrial.NONE) return;
        publishDisplayTrial();
        if (action == LabDisplayTrial.TEXT) {
            sendBusiness("AI_SUBTITLE", 5, new JSONObject().put("sid", displayTrial.id).put("mode", 3).put("status", 0)
                .put("content", new JSONObject().put("source_transcript", "SDK 纯文字测试：中文 ABC 123。此项不录音。")),
                "display-text-" + displayTrial.id);
        } else if (action == LabDisplayTrial.EXIT) sendDisplayTrialExit();
        else if (displayTrial.owns(activeCommand)) {
            result.getJSONObject("last_command").put("status", displayTrial.issue.isEmpty() && displayTrial.textSent && displayTrial.exitSent ? "completed" : "failed");
            stage("display_trial_transport_finished", true);
        }
    }
    private void requestSystemPairing(String id) throws Exception {
        if (pairingRequested) throw new IllegalStateException("Pairing already requested in this session");
        pairingRequested=true;pairingWindowOpen=true;
        if(manager.getAdapter().getRemoteDevice(address).getBondState()==BluetoothDevice.BOND_BONDED
                &&(pairingModeAttempt||repairUnbonded)){
            // Native createBond short-circuits on a stale Android bond. Ask the encrypted
            // characteristic instead, and wait for the SDK's actual bond event below.
            stage("system_pairing_requested","Encrypted characteristic; existing phone bond is not confirmation");
            VendorPairingTrigger.send(runtime,address,success->handler.post(()->{
                try{
                    if(finished||finishing||!id.equals(activeCommand))return;
                    result.put("pairing_trigger_written",success);stage("pairing_trigger_written",success);
                    if(!success){pairingWindowOpen=false;result.getJSONObject("last_command").put("status","failed");}
                }catch(Exception e){complete("failed",rootError(e));}
            }));
        }else{
            stage("system_pairing_requested","Original SDK I3.L.h");
            runtime.type("I3.L").getMethod("h",runtime.type("I3.D")).invoke(null,targetDevice);
        }
        handler.postDelayed(()->{
            try{
                if(!finished&&id.equals(activeCommand)&&"pending".equals(result.getJSONObject("last_command").optString("status"))){
                    pairingWindowOpen=false;
                    result.getJSONObject("last_command").put("status","failed");stage("system_pairing_timeout",true);
                }
            }catch(Exception e){complete("failed",rootError(e));}
        },65000);
    }

    private boolean handleAnswerTrialVoice(BusinessEnvelope wire) throws Exception {
        if(answerTrial==null)return false;
        stage("answer_trial_voice_event",new JSONObject().put("type",wire.type).put("data_bytes",wire.dataBytes));
        advanceAnswerTrial(answerTrial.voice(wire.type,SystemClock.elapsedRealtime()));
        return true;
    }
    private boolean currentAsrTrial(LabAsrTrial trial){
        return !finished&&!finishing&&asrTrial==trial&&!trial.done&&trial.session.equals(result.optString("session_id"));
    }
    private void beginAsrTrial(String id,int silenceDurationMs,int captureWindowMs,int continuationGraceMs)throws Exception{
        if(!id.matches("[A-Za-z0-9-]{1,80}"))throw new IllegalStateException("Invalid ASR command identity");
        if(!getPackageName().equals("dev.xr.rayneo.sdklab")||!connectionReady()||standbyEnabled||voiceArmed||voiceRecording||voicePreparing
            ||recordingPreparing||(recorder!=null&&recorder.busy())||displayTrial!=null||answerTrial!=null
            ||(settingsQuery!=null&&!settingsQuery.done)||(dashboardQuery!=null&&!dashboardQuery.done)||brightnessJournal().getBoolean("pending",false)
            ||autoLockPending()||crownPending()||headPending()||wakePending()||assistantPrefs().getBoolean("auto_standby",true)
            ||(asrTrial!=null&&!asrTrial.done))throw new IllegalStateException("Requires idle ready Lab, auto standby off and no pending restore");
        JSONObject config=CloudConfig.load(this);
        if(config.optString("dashscope_key").isEmpty())throw new IllegalStateException("Configure ASR provider first");
        StreamingAsr.endpoint(config);
        asrTrial=new LabAsrTrial(id,result.getString("session_id"),SystemClock.elapsedRealtime(),silenceDurationMs,captureWindowMs,continuationGraceMs);
        asrPublished="";publishAsrTrial();stage("asr_trial_armed",true);
    }
    private void closeLabAsr(){
        StreamingAsr current=labAsrStream;labAsrStream=null;if(current!=null)current.close();
    }
    private void startLabAsr(LabAsrTrial trial)throws Exception{
        // Copy only in memory. The diagnostic never saves provider or standby preferences.
        JSONObject config=new JSONObject(CloudConfig.load(this).toString());config.put("streaming_asr",true);
        labAsrStream=new StreamingAsr(config,new StreamingAsr.Listener(){
            public void timing(String name,String detail,long at){handler.post(()->{
                if(!currentAsrTrial(trial))return;trial.mark(name,at);
                if(name.equals("asr_endpoint")&&trial.endpointReason.equals("not_received"))trial.endpointReason=detail;
                try{publishAsrTrial();}catch(Exception e){complete("failed",rootError(e));}
            });}
            public void event(String name,Object value){handler.post(()->{
                if(!currentAsrTrial(trial))return;
                try{
                    if(name.equals("stream_upload_started"))result.put("cloud_upload_requests_total",result.optLong("cloud_upload_requests_total")+1);
                    stage("asr_trial_"+name,value);
                }catch(Exception e){complete("failed",rootError(e));}
            });}
            public void text(String value,boolean isFinal){handler.post(()->{
                if(!currentAsrTrial(trial))return;
                // Metadata only here; final transcript is retained in the independent ASR receipt.
                trial.mark(isFinal?"final_text_received":"first_partial_received",SystemClock.elapsedRealtime());
                try{publishAsrTrial();}catch(Exception e){complete("failed",rootError(e));}
            });}
            public void endpoint(){handler.post(()->{
                if(!currentAsrTrial(trial))return;
                try{applyAsrActions(trial,trial.endpoint(SystemClock.elapsedRealtime()));}catch(Exception e){complete("failed",rootError(e));}
            });}
            public void failed(String reason,boolean uploaded){handler.post(()->{
                if(!currentAsrTrial(trial))return;
                try{applyAsrActions(trial,trial.finish(reason,false,null,SystemClock.elapsedRealtime()));}catch(Exception e){complete("failed",rootError(e));}
            });}
            public void completed(JSONObject asr){handler.post(()->{
                if(!currentAsrTrial(trial))return;
                try{applyAsrActions(trial,trial.finish("",true,asr,SystemClock.elapsedRealtime()));}catch(Exception e){complete("failed",rootError(e));}
            });}
        },trial.requestedSilenceDurationMs,trial.captureWindowMs,trial.continuationGraceMs);
        result.put("cloud_stream_connections_total",result.optLong("cloud_stream_connections_total")+1);
        labAsrStream.start();
    }
    private void applyAsrActions(LabAsrTrial trial,int actions)throws Exception{
        if(asrTrial!=trial)return;
        if((actions&LabAsrTrial.CLOSE)!=0)closeLabAsr();
        if((actions&LabAsrTrial.START)!=0){
            try{startLabAsr(trial);sendBusiness("VOICE_ASSISTANT",2,new JSONObject().put("rc",1),trial.request(LabAsrTrial.START));}
            catch(Exception e){
                trial.startSent=false;
                applyAsrActions(trial,trial.finish("capture_start_exception_"+e.getClass().getSimpleName(),false,null,SystemClock.elapsedRealtime()));
                return;
            }
        }
        if((actions&LabAsrTrial.STOP)!=0){
            try{sendBusiness("VOICE_ASSISTANT",2,new JSONObject().put("rc",2),trial.request(LabAsrTrial.STOP));}
            catch(Exception e){applyAsrActions(trial,trial.sent(trial.request(LabAsrTrial.STOP),false,SystemClock.elapsedRealtime()));}
        }
        if((actions&LabAsrTrial.EXIT)!=0){
            try{sendBusiness("VOICE_ASSISTANT",7,new JSONObject().put("rc",1),trial.request(LabAsrTrial.EXIT));}
            catch(Exception e){applyAsrActions(trial,trial.sent(trial.request(LabAsrTrial.EXIT),false,SystemClock.elapsedRealtime()));}
        }
        publishAsrTrial();
    }
    private boolean handleAsrTrialVoice(BusinessEnvelope wire)throws Exception{
        LabAsrTrial trial=asrTrial;
        if(trial==null||trial.done)return false;
        long now=SystemClock.elapsedRealtime();
        if(wire.type==3){
            if(trial.capturing&&wire.audio!=null&&wire.audio.length>0){
                trial.audio(wire.dataBytes,now);
                if(labAsrStream==null||!labAsrStream.offer(wire.audio)||trial.bytes>1048576)
                    applyAsrActions(trial,trial.finish("audio_queue_or_size_limit",false,null,now));
                else if(trial.packets==1)publishAsrTrial();
            }
        }else{
            int actions=trial.voice(wire.type,now);
            if((actions&LabAsrTrial.START)!=0){
                JSONObject wake=null;try{wake=new JSONObject(wire.json);}catch(org.json.JSONException ignored){}
                trial.rememberWake(wake);
            }
            applyAsrActions(trial,actions);
        }
        return true;
    }
    private void publishAsrTrial()throws Exception{
        if(asrTrial==null)return;
        JSONObject state=asrTrial.snapshot();String serialized=state.toString(2);
        if(serialized.equals(asrPublished))return;
        File dir=new File(getFilesDir(),"lab-asr");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("ASR receipt directory");
        File temporary=new File(dir,asrTrial.id+".tmp");
        try(FileOutputStream out=new FileOutputStream(temporary)){out.write(serialized.getBytes("UTF-8"));out.getFD().sync();}
        if(!temporary.renameTo(new File(dir,asrTrial.id+".json")))throw new IOException("ASR receipt rename");
        asrPublished=serialized;result.put("lab_asr_trial",state);
        if(asrTrial.done&&asrTrial.id.equals(activeCommand))result.getJSONObject("last_command").put("status",asrTrial.passed()?"completed":"failed");
        stage("asr_trial_state",state);
    }
    private void voiceEvent(BusinessEnvelope wire) throws Exception {
        if(handleAsrTrialVoice(wire))return;
        if(handleAnswerTrialVoice(wire))return;
        if(handleLabNativeReadingExit(wire.type))return;
        if(wire.type==8&&labNativeMode()&&labNativeRound==null&&voiceArmed&&!voiceRecording){
            stage("lab_native_pre_wake_exit_ignored",true);return;
        }
        // Hardware emits type8 (close old answer) then type1 ~20ms later on a fresh wake.
        // Idle owns no recording to finish: retain the armed listener and invalidate old page exit.
        if (wire.type == 8 && standbyReady && !voiceRecording) {
            nativeDisplayUntil = 0;
            if(!labNativeStandby)activeCommand = null;
            stage("standby_old_page_exited", true); return;
        }
        if (wire.type == 8 && nativeReply && !standbyReady && !voicePreparing
                && VoiceRecordingAction.ownsVoiceExit(voiceCommand,activeCommand,activeCommandKind,
                    result.optJSONObject("last_command") != null && "pending".equals(result.getJSONObject("last_command").optString("status")))) {
            archiveVoiceCancellation("cancelled_by_device");
            if (cloudCancellation != null) cloudCancellation.cancel(); closeStreaming(); sendContinuations.clear();
            voiceArmed = false; voiceRecording = false; cloudVoice = false; OpusAudio.clear(voiceAudio);
            voiceTiming(voiceCommand,"device_exit_cancels_active_round",SystemClock.elapsedRealtime());
            endVoiceRound(voiceCommand,"cancelled_by_device");
            voiceStopAt = SystemClock.elapsedRealtime(); voiceCommand = null; activeCommand = null;
            result.getJSONObject("last_command").put("status", "cancelled");
            result.getJSONObject("voice_test").put("phase", "cancelled").put("reason", "Glasses exited native assistant");
            clearLabAssistantConversation("device_exit_cancelled_round");
            stage("native_round_cancelled_by_glasses", true); rearmStandby(); return;
        }
        if (wire.type == 3) {
            result.put("audio_receive_packets_total", result.optLong("audio_receive_packets_total") + 1);
            if (!voiceRecording && SystemClock.elapsedRealtime() - voiceStopAt > 3000)
                result.put("idle_audio_packets", result.optLong("idle_audio_packets") + 1);
        }
        if (wire.type != 3) stage("voice_event", new JSONObject().put("type", wire.type).put("data_bytes", wire.dataBytes));
        if (wire.type == 3 && !voiceRecording && voiceCommand != null) voiceAfterStop++;
        if (!voiceArmed) { dropWake(wire.type, standbyEnabled ? "standby_not_armed" : "standby_off"); return; }
        if (VoiceWakePolicy.accepts(wire.type, voiceArmed, voiceRecording,
                standbyEnabled && standbyReady && nativeReply && nativeAnswerContinuable())) {
            if(!activateStandbyWake(wire.type)){dropWake(wire.type,"standby_wake_refused");return;}
            if(labNativeMode()){
                if(!currentRound(voiceCommand))return;
                try{
                    if(wire.type==1){
                        resetLabNativeRound();
                        labNativeRound=new NativeAnswerRound(voiceCommand,result.getString("session_id"),labNativeUser,labNativeDialog,new JSONObject(wire.json));
                        result.put("native_answer_context",labNativeRound.metadata());
                    }else if(!labNativeContinuationCandidate||labNativeContinuationLinkType==0)
                        throw new IllegalStateException("Lab continuation has no owned link context");
                }catch(Exception e){failStreaming(voiceCommand,"Unsupported native wake context",false);return;}
            }
            beginVoiceRound();voiceRounds.trigger(voiceCommand,wire.type==1?"hardware_type1":"continuation_type11");voiceTiming(voiceCommand,"wake_received",SystemClock.elapsedRealtime());
            if(!labNativeContinuationCandidate)nativeDisplayUntil = 0;
            startVoiceCapture();
            if(labNativeContinuationCandidate){
                final String candidate=voiceCommand;
                handler.postDelayed(()->{
                    if(labNativeContinuationCandidate&&currentRound(candidate))
                        failStreaming(candidate,"续问候选未检测到有效语音",streamingAsr!=null&&streamingAsr.uploadStarted());
                    // The round advertises followup_due_elapsed_ms from labNativeFollowupSeconds;
                    // cutting the microphone at a hardcoded 4s made that receipt untrue and gave
                    // the user far less time to continue than the official 10s window.
                },labNativeFollowupSeconds*1000L);
            }
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
            if (voicePackets == 1) {voiceTiming(voiceCommand,"first_audio_received",SystemClock.elapsedRealtime());stage("voice_first_audio", wire.dataBytes);}
            if (voiceBytes > 1048576) finishVoice("Audio byte limit reached");
        } else if (wire.type == 8) finishVoice("Glasses exited voice mode");
    }

    private void startVoiceCapture() throws Exception {
        beginVoiceRound();voiceRounds.trigger(voiceCommand,"manual_diagnostic");voiceTiming(voiceCommand,"capture_requested",SystemClock.elapsedRealtime());
        voiceRecording = true;
        voiceRounds.capturePolicy(voiceCommand,"fixed_eight_seconds");
        if (cloudVoice && nativeReply) {
            JSONObject config = CloudConfig.load(this);
            if(labNativeMode()){
                labNativeAutoExitSeconds=config.optInt("assistant_auto_exit_seconds",15);
                labNativeDisplayWaitSeconds=displayWaitSeconds(config);
                labNativeFollowupSeconds=config.optInt("assistant_followup_seconds",10);
                asrPartialPrefixStep=config.optInt("asr_partial_prefix_step",4);
                asrPartialIdleMs=config.optInt("asr_partial_idle_ms",1200);
                asrPartialMaxPerRound=config.optInt("asr_partial_max_per_round",12);
            }
            if (labNativeMode()||config.optBoolean("streaming_asr")) {
                voiceRounds.capturePolicy(voiceCommand,labNativeMode()?"streaming_continuation_grace_2000ms":"streaming_server_vad");
                startStreaming(config, voiceCommand);
            }
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
        voiceTiming(ending,"capture_stop_requested",SystemClock.elapsedRealtime());
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
                        boolean diagnosticOk=ok&&!cloudVoice;
                        if (ending.equals(activeCommand)) result.getJSONObject("last_command").put("status", diagnosticOk ? "completed" : "failed");
                        OpusAudio.clear(voiceAudio); cloudVoice = false;
                        endVoiceRound(ending,diagnosticOk?"audio_diagnostic_finished":"failed");
                    }
                    stage("voice_test_finished", ok);
                    if (!cloudVoice && standbyEnabled && !ok) rearmStandby();
                } catch (Exception e) { complete("failed", rootError(e)); }
            }, 3000);
        } catch (Exception e) { complete("failed", rootError(e)); }
    }

    private void failVoicePreparation(String id, String reason) {
        if (finished || finishing || !id.equals(activeCommand)) return;
        voicePreparing = false; voiceArmed = false; cloudVoice = false; standbyEnabled = false; standbyReady = false; labNativeStandby=false; OpusAudio.clear(voiceAudio);
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
    private void resetLabNativeRound(){
        if(labNativeAutoExit!=null){handler.removeCallbacks(labNativeAutoExit);labNativeAutoExit=null;}
        if(labNativeExitConfirmation!=null){handler.removeCallbacks(labNativeExitConfirmation);labNativeExitConfirmation=null;}
        // The third timer. This method exists to cancel the round's timers and cancelled the other
        // two; leaving this one out is what let a dead round re-open its reading window 55 seconds
        // after the glasses had already exited (observed 2026-09-21).
        cancelDisplayCompleteWait("round_reset");
        labNativeRound=null;labNativeStopStream=null;nativeDisplayUntil=0;nativeFollowupUntil=0;labNativeContinuationLinkType=0;
        labNativeContinuationCandidate=false;labNativeContinuationUser="";labNativeContinuationDialog="";
        result.remove("lab_native_exit_send_completed");result.remove("lab_native_reading");result.remove("native_answer_context");
        result.remove("knowledge_waiting_preface");
    }
    /** Shared by the wake gate and activateStandbyWake. Keeping one copy is the point: when the
     * two diverged, the type 11 that starts the reading windows was rejected by the gate before
     * the inner code that handles it could run. See DisplayedAnswerPolicy. */
    /** The display-wait fallback exists only so a lost receipt cannot block the round forever
     * It must sit well above the longest real display time, measured 35.6s on our
     * own link and 33.4s on the official app. A shorter value does two kinds of damage: it closes
     * the page while the answer is still scrolling, and because the page is destroyed the glasses
     * never reach display completion, so the receipt never arrives at all. The 8s default shipped
     * on 2026-09-20 truncated a 35s answer at 23s and produced zero type 11 for the round, which
     * is how "the glasses do not report type 11" was wrongly concluded. Values below the floor are
     * never correct, so the floor is enforced rather than trusted from configuration. */
    /** How long to wait for the receipt before closing the round from the fallback.
     *
     * <p>A fixed bound cannot work: the answer limit in reading mode is 2000 code points and each
     * chunk carries 480 UTF-8 bytes, so a full-length Chinese answer is about 12 chunks, while a
     * chunk was measured taking 12.5s to scroll (37.37s for 3 chunks on 2026-09-20). A full answer
     * therefore needs over two minutes on the lens, and any fixed 8s or 60s bound truncates it --
     * and because truncating destroys the page, the receipt then never arrives at all.
     *
     * <p>This is not the length-based reading estimate that was deleted with AssistantReadingPolicy.
     * That one decided when to close a page that was displaying correctly. This only sizes a safety
     * bound for a signal that should arrive on its own; in the normal path it never fires. The
     * per-chunk allowance is double the measured rate, and the cap keeps a genuinely lost receipt
     * from holding the round for an unbounded time. */
    private long displayWaitMs(int chunks){
        return Math.min(300000L,Math.max(labNativeDisplayWaitSeconds*1000L,chunks*25000L+30000L));
    }
    private static int displayWaitSeconds(org.json.JSONObject source){
        return Math.max(45,source.optInt("assistant_display_wait_seconds",60));
    }
    /** Every discarded wake must leave a trace.
     *
     * <p>A wake used to vanish with no record at all, so "press the button, glasses say please
     * connect" could only be found by a person testing it. On 2026-09-20 that happened twice for
     * two different reasons and both cost a full test round to diagnose. One counter plus one
     * stage per drop makes the whole class visible from the session receipt straight away.
     *
     * <p>A wake is also the strongest possible signal that the user wants the assistant now, so
     * it cancels any remaining auto-restore backoff. The recorder holds that backoff for three
     * seconds after a recording is saved to let the transport release; a user pressing the button
     * inside that window should not have to wait it out. */
    private void dropWake(int type,String reason)throws Exception{
        if(type!=1&&type!=11)return;
        result.put("wakes_dropped_total",result.optLong("wakes_dropped_total")+1);
        stage("voice_wake_dropped",new JSONObject().put("type",type).put("reason",reason)
            .put("standby_enabled",standbyEnabled).put("standby_ready",standbyReady)
            .put("recorder_busy",recorder!=null&&recorder.busy())
            .put("restore_backoff_ms",Math.max(0,nextStandbyAttempt-SystemClock.elapsedRealtime())));
        // recorder.busy() is already false at phase "saved", so the recorder alone cannot say
        // whether a recording still needs the transport; recordingTransportOwned can, and it is
        // cleared in the same step that restores standby. Under option 甲 nothing tears the link
        // down any more, but the guard stays: a preparation that is still claiming the channel is
        // not a moment to re-arm standby behind its back.
        if(type==1&&!standbyEnabled&&!recordingPreparing&&!recordingTransportOwned
            &&(recorder==null||!recorder.busy())
            &&SystemClock.elapsedRealtime()<nextStandbyAttempt){
            nextStandbyAttempt=0;
            stage("standby_restore_expedited",reason);
            restoreStandbyIfNeeded();
        }
    }
    private boolean nativeAnswerContinuable(){
        return DisplayedAnswerPolicy.continuable(labNativeReadingActive(),awaitingDisplayComplete,
            SystemClock.elapsedRealtime(),nativeFollowupUntil);
    }
    private boolean activateStandbyWake(int type)throws Exception{
        if(!standbyEnabled||!standbyReady)return true;
        if(labNativeStandby){
            // The receipt means "the lens finished scrolling"; that is independent of whether we
            // want to listen for a follow-up. Folding labNativeFollowupEnabled into the same test
            // made a disabled follow-up also discard the receipt, leaving the round to close from
            // the display-wait fallback -- the same defect as the wake gate, one branch over.
            boolean displayComplete=type==11&&nativeAnswerContinuable();
            if(displayComplete&&awaitingDisplayComplete)startReadingWindows();
            boolean continuation=displayComplete&&labNativeFollowupEnabled;
            if(type!=1&&!continuation)return false;
            if(type==1&&!labAssistantConversationActive)startLabAssistantConversation("new_wake");
            JSONObject host=result.optJSONObject("last_command");
            if(host!=null&&"pending".equals(host.optString("status"))){
                // Every callback that could close a voice round is keyed on voiceCommand. Once that
                // has been replaced the pending command can never complete, and refusing wakes on it
                // blocks the session for good without self-healing. Non-voice commands
                // such as recording keep their own identity and must still block.
                if(!host.optString("kind").startsWith("voice-")||host.optString("id").equals(voiceCommand)){
                    stage("lab_standby_wake_busy",host.optString("id"));return false;}
                host.put("status","abandoned").put("reason","round_no_longer_current");
                stage("lab_standby_stale_command_cleared",host.optString("id"));
            }
            if(continuation){
                // Type 11 is only a candidate for the next round until VAD confirms speech.
                // Keeping the delivered round and its timer alive prevents a silent/ambient
                // firmware transition from destroying the answer page and forcing a black exit.
                labNativeContinuationLinkType=labNativeRound.linkType;
                labNativeContinuationUser=labNativeRound.user;
                labNativeContinuationDialog=labNativeRound.dialog;
                if(labNativeAutoExit!=null){handler.removeCallbacks(labNativeAutoExit);labNativeAutoExit=null;}
                labNativeContinuationCandidate=true;
            }else resetLabNativeRound();
        }
        standbyReady=false;standbyRounds++;
        activeCommand=voiceCommand;activeCommandKind=labNativeStandby?"voice-native":"voice-cloud";
        result.put("last_command",new JSONObject().put("id",voiceCommand).put("kind",activeCommandKind).put("status","pending").put("started_ms",SystemClock.elapsedRealtime()));
        publishStandby("recording");return true;
    }
    private void promoteLabNativeContinuation(String command)throws Exception{
        if(!labNativeContinuationCandidate||!currentRound(command))return;
        int link=labNativeContinuationLinkType;String user=labNativeContinuationUser,dialog=labNativeContinuationDialog;
        resetLabNativeRound();
        labNativeRound=new NativeAnswerRound(command,result.getString("session_id"),user,dialog,
            new JSONObject().put("linkType",link));
        result.put("native_answer_context",labNativeRound.metadata());
        stage("lab_native_followup_speech_confirmed",command);
    }
    private boolean recoverSilentLabNativeContinuation(String command,String reason)throws Exception{
        if(!labNativeContinuationCandidate||!command.equals(activeCommand)||labNativeRound==null||!labNativeReadingActive())return false;
        labNativeContinuationCandidate=false;labNativeContinuationLinkType=0;
        labNativeContinuationUser="";labNativeContinuationDialog="";
        // Official behaviour, adopted by user decision 2026-09-20: the receipt opens exactly one
        // listening window and silence at its end closes the page. Restarting a second reading
        // window here held the answer for followupSeconds + autoExitSeconds after display
        // completion (30s on defaults) where the official app exits at 10s. The user can still
        // keep reading by paging with the crown.
        long followupWindow=labNativeFollowupSeconds*1000L;
        endVoiceRound(command,"followup_no_speech");
        result.put("continuation_no_speech",new JSONObject().put("command_id",command).put("reason",reason)
            .put("reading_command_id",labNativeRound.command).put("exit_sent",true)
            .put("reading_window_restarted",false)
            .put("followup_window_ms",followupWindow));
        result.put("last_command",new JSONObject().put("id",labNativeRound.command).put("kind","voice-native").put("status","completed"));
        result.getJSONObject("voice_test").put("phase","followup_timeout").put("reason",reason);
        nativeFollowupUntil=0;nativeDisplayUntil=0;
        if(labNativeAutoExit!=null){handler.removeCallbacks(labNativeAutoExit);labNativeAutoExit=null;}
        stage("lab_native_followup_no_speech_exit",command);
        // 2026-09-22: DO NOT stop sending type 7 here. That was tried and the device data
        // refuted it the same day: omitting the frame kept the answer page open.
        // Without the frame the glasses stayed on the answer page and only emitted their own
        // type 8 exit 23.8 seconds after the phone had finished the round, and the wake gap was
        // still there -- in fact longer. The gap is not caused by this frame: the same session
        // shows 5903 ms of it on the handoffVoiceExit path, which does send type 7.
        requestLabNativeReadingExit("auto_exit_requested");return true;
    }
    private void rearmStandby() throws Exception {
        if (!standbyEnabled || finished || finishing) return;
        if (!persistentSession && standbyRounds >= 10) { stopStandby(); stage("standby_round_limit", 10); return; }
        if(voiceRounds!=null){voiceRounds.listenerRearmed(SystemClock.elapsedRealtime());publishVoiceTiming();}
        // Idle is only an event subscription: no recorder command, no cloud call, no decoder.
        voiceCommand = UUID.randomUUID().toString(); cloudVoice = true; voiceArmed = true; voiceRecording = false;
        voicePackets = 0; voiceBytes = 0; voiceAfterStop = 0; OpusAudio.clear(voiceAudio);
        standbyReady = true;
        result.put("voice_test", new JSONObject().put("phase", "awaiting_wakeup").put("audio_source", "glasses")
            .put("trigger", "glasses_wakeup").put("cloud_enabled", true).put("audio_saved", false).put("audio_uploaded", false));
        publishStandby("idle"); stage("standby_waiting_for_wakeup", true);
    }
    private void armLabNativeFollowup() throws Exception {
        if(!labNativeFollowupEnabled||!labNativeStandby||!standbyEnabled||finished||finishing||!labNativeReadingActive())return;
        if(voiceRounds!=null){voiceRounds.listenerRearmed(SystemClock.elapsedRealtime());publishVoiceTiming();}
        voiceCommand=UUID.randomUUID().toString();cloudVoice=true;voiceArmed=true;voiceRecording=false;
        voicePackets=0;voiceBytes=0;voiceAfterStop=0;OpusAudio.clear(voiceAudio);standbyReady=true;
        result.put("voice_test",new JSONObject().put("phase","awaiting_followup").put("audio_source","glasses")
            .put("trigger","continuation_type11").put("cloud_enabled",true).put("audio_saved",false).put("audio_uploaded",false));
        publishStandby("followup_window");stage("lab_native_followup_armed",new JSONObject()
            .put("seconds",labNativeFollowupSeconds).put("command_id",labNativeRound.command));
    }
    private void stopStandby() throws Exception {
        boolean labReadingExit=requestLabNativeReadingExit();
        if(!labReadingExit)archiveVoiceCancellation("cancelled_by_host");
        if(!labReadingExit)endVoiceRound(voiceCommand,"cancelled_by_host");
        standbyEnabled = false; standbyReady = false; labNativeStandby=false;
        nativeDisplayUntil = 0;
        sendContinuations.clear();
        if (cloudCancellation != null) cloudCancellation.cancel();
        closeStreaming();
        if (voiceRecording) {
            sendBusiness("VOICE_ASSISTANT", 2, new JSONObject().put("rc", 2), "voice-stop-" + voiceCommand);
        }
        if (!labReadingExit&&(voiceRecording || nativeReply) && voiceCommand != null) {
            sendBusiness("VOICE_ASSISTANT", 7, new JSONObject().put("rc", 1), "voice-exit-" + voiceCommand);
        }
        voiceArmed = false; voiceRecording = false; voicePreparing = false; cloudVoice = false;
        voiceStopAt = SystemClock.elapsedRealtime();
        OpusAudio.clear(voiceAudio);
        if (!labReadingExit&&result.optJSONObject("voice_test") != null) result.getJSONObject("voice_test").put("phase", "cancelled");
        voiceCommand = null; publishStandby("disabled");
        clearLabAssistantConversation("standby_stopped");
    }

    private void startLabAssistantConversation(String reason)throws Exception{
        labAssistantConversation.start();labAssistantConversationActive=true;
        result.put("assistant_conversation",labAssistantConversation.metadata().put("state","active").put("last_event",reason));
    }
    private void clearLabAssistantConversation(String reason)throws Exception{
        if(!labAssistantConversationActive)return;
        labAssistantConversation.clear();labAssistantConversationActive=false;
        result.put("assistant_conversation",labAssistantConversation.metadata().put("state","cleared").put("last_event",reason));
    }

    private void archiveVoiceCancellation(String reason) {
        NativeAnswerRound round=labNativeRound;
        // A control command may already own activeCommand; the captured voice identity remains authoritative.
        if(!getPackageName().equals("dev.xr.rayneo.sdklab")||round==null||round.terminal()
                ||voiceCommand==null||!round.same(voiceCommand,result.optString("session_id"))||standbyReady)return;
        try{
            JSONObject voice=result.optJSONObject("voice_test");
            boolean uploaded=voice!=null&&voice.optBoolean("upload_started");
            JSONObject cancelled=VoiceAnswerArchive.failed(result.optJSONObject("glasses_cloud"),round.session,round.command,reason,uploaded)
                .put("status",reason.equals("session_failed")?"failed":"cancelled");
            round.finishOnce();result.put("glasses_cloud",cancelled);writeCloudResult(cancelled);
        }catch(Exception e){try{result.put("voice_answer_archive_error",e.getClass().getSimpleName());}catch(Exception ignored){}}
    }

    private void writeCloudResult(JSONObject value) throws Exception {
        if(getPackageName().equals("dev.xr.rayneo.sdklab")&&labNativeRound!=null
                &&labNativeRound.same(value.optString("job_id"),value.optString("session_id"))){
            try{VoiceAnswerArchive.save(new File(getFilesDir(),"voice-answers"),value);result.remove("voice_answer_archive_error");}
            catch(Exception e){result.put("voice_answer_archive_error",e.getClass().getSimpleName());}
        }
        File temp = new File(getFilesDir(), "glasses-cloud-result.tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(value.toString(2).getBytes("UTF-8")); out.getFD().sync();
        }
        if (!temp.renameTo(new File(getFilesDir(), "cloud-result.json"))) throw new IOException("Cloud result rename");
    }

    private void traceReading(String command, String event, JSONObject details) {
        if (!getPackageName().equals("dev.xr.rayneo.sdklab")) return;
        try {
            ReadingExitJournal.append(new File(getFilesDir(), "reading-exits"),
                result.optString("session_id"), command, event, SystemClock.elapsedRealtime(), details);
            result.remove("reading_trace_error");
        } catch (Exception error) {
            try { result.put("reading_trace_error", error.getClass().getSimpleName()); }
            catch (Exception ignored) { }
        }
    }

    private void closeStreaming() {
        StreamingAsr current = streamingAsr; streamingAsr = null;
        if (current != null) current.close();
        try { result.put("asr_stream_active", false); } catch (Exception ignored) {}
    }
    private boolean currentRound(String command) {
        return !finished && !finishing && command.equals(activeCommand) && command.equals(voiceCommand)
            &&(!labNativeMode()||(result.optJSONObject("last_command")!=null&&"pending".equals(result.optJSONObject("last_command").optString("status"))));
    }
    private void stopStreamingCapture(String command) throws Exception {
        if (!currentRound(command)) return;
        if (voiceRecording) {
            voiceTiming(command,"capture_stop_requested",SystemClock.elapsedRealtime());
            voiceRecording = false; voiceArmed = false; voiceStopAt = SystemClock.elapsedRealtime();
            if(labNativeMode()&&labNativeRound!=null&&!labNativeContinuationCandidate){
                labNativeStopStream=streamingAsr;
                sendBusiness("VOICE_ASSISTANT",2,new JSONObject().put("rc",2),labNativeRound.request("stop"));
            }else sendBusiness("VOICE_ASSISTANT", 2, new JSONObject().put("rc", 2), "voice-stop-" + command);
            result.getJSONObject("voice_test").put("phase", "finalizing_transcript");
            stage("stream_microphone_stopped", true);
        }
    }
    private void failStreaming(String command, String reason, boolean uploadAttempted) {
        if (!currentRound(command)) return;
        try {
            stopStreamingCapture(command); closeStreaming(); cloudVoice = false;
            if(recoverSilentLabNativeContinuation(command,reason))return;
            // 识别阶段失败也在镜片上说一句，而不是静默关页。
            // 回答阶段处理只覆盖「已经拿到文字之后」的失败；连不上 ASR、或 ASR 中途不再返回
            // 都走到这里，此前一律无声退出——用户看到的就是「闪一下就关了」。
            // pipeline 必须带 asr.text：sendNativeAnswer 会读它当 query，
            // 而识别失败时这个字段常常根本不存在（连不上时一个字都没有）。
            if(CloudFailureNotice.asrNoticeable(reason)&&labNativeMode()&&labNativeRound!=null){
                String notice=CloudFailureNotice.asrLensText(reason);
                JSONObject heard=new JSONObject().put("text",result.optString("submitted_transcript",""));
                JSONObject pipeline=new JSONObject().put("status","failed").put("error",reason)
                    .put("job_id",command).put("session_id",result.optString("session_id"))
                    .put("provider","local_error_notice").put("text",notice).put("asr",heard)
                    .put("answer",new JSONObject().put("status","failed")
                        .put("provider","local_error_notice").put("text",notice));
                voiceArmed=false;
                clearLabAssistantConversation("round_failed");
                traceReading(command,"round_failed",new JSONObject().put("provider","asr")
                    .put("notice","requested").put("error",reason));
                result.put("failure_notices_total",result.optLong("failure_notices_total")+1);
                stage("asr_failure_notice_requested",new JSONObject().put("command",command)
                    .put("error",reason).put("heard",heard.optString("text")));
                result.put("glasses_cloud",pipeline); writeCloudResult(pipeline);
                sendNativeAnswer(pipeline,command);
                return;
            }
            if(labNativeMode()){
                voiceArmed=false;
                if(labNativeRound!=null)labNativeRound.finishOnce();
                sendContinuations.clear();
                clearLabAssistantConversation("round_failed");
                traceReading(command,"round_failed",new JSONObject().put("provider",
                    result.optJSONObject("glasses_cloud")==null?"unknown":result.optJSONObject("glasses_cloud").optString("provider","unknown")));
            }
            endVoiceRound(command,"failed");
            if(labNativeMode())traceReading(command,"type7_requested",new JSONObject().put("reason","round_failed"));
            sendBusiness("VOICE_ASSISTANT", 7, new JSONObject().put("rc", 1),
                labNativeMode()?"lab-native-exit-"+command:"voice-exit-"+command);
            JSONObject failure = VoiceAnswerArchive.failed(result.optJSONObject("glasses_cloud"),
                result.getString("session_id"),command,reason,uploadAttempted);
            result.put("glasses_cloud", failure); writeCloudResult(failure);
            result.getJSONObject("last_command").put("status", "failed");
            result.getJSONObject("voice_test").put("phase", "failed").put("reason", reason);
            stage("stream_failed", failure); rearmStandby();
        } catch (Exception e) { complete("failed", rootError(e)); }
    }
    private void startStreaming(JSONObject config, String command) throws Exception {
        closeStreaming(); partialGate = new AsrPartialGate(asrPartialPrefixStep, asrPartialIdleMs, asrPartialMaxPerRound);
        result.put("cloud_stream_connections_total", result.optLong("cloud_stream_connections_total") + 1).put("asr_stream_active", true);
        result.remove("submitted_transcript"); result.remove("submitted_transcript_final");
        result.getJSONObject("voice_test").put("asr_mode", "streaming");
        final VoiceRoundJournal roundTiming=voiceRounds;
        streamingAsr = new StreamingAsr(config, new StreamingAsr.Listener() {
            public void timing(String name,String detail,long at) {
                if(roundTiming!=null){
                    if(name.equals("asr_endpoint"))roundTiming.endpoint(command,detail,at);
                    else roundTiming.mark(command,name,at);
                }
                handler.post(() -> {if(currentRound(command))publishVoiceTiming();});
            }
            public void event(String name, Object value) { handler.post(() -> { try {
                if (!currentRound(command)) return;
                if(name.equals("stream_speech_started"))promoteLabNativeContinuation(command);
                if (name.equals("stream_upload_started")) {
                    result.put("cloud_upload_requests_total", result.optLong("cloud_upload_requests_total") + 1);
                    result.getJSONObject("voice_test").put("upload_started", true);
                }
                stage(name, value);
            } catch (Exception e) { complete("failed", rootError(e)); } }); }
            public void text(String value, boolean isFinal) { handler.post(() -> { try {
                if (!currentRound(command) || value.isEmpty()) return;
                long now = SystemClock.elapsedRealtime();
                if (!isFinal && !partialGate.accept(value, now)) return;
                if (value.getBytes("UTF-8").length > 512) { failStreaming(command, "识别文本超过问题区限制", true); return; }
                partialGate.commit(value, now);
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
        },700,0,labNativeMode()?2000:0);
        streamingAsr.start();
    }
    private void runStreamAnswer(JSONObject config, String command, JSONObject asr) throws Exception {
        final String session = result.getString("session_id");
        final boolean readingTrial=labNativeMode();
        final JSONArray conversationHistory=readingTrial&&labAssistantConversationActive?labAssistantConversation.history():new JSONArray();
        if(readingTrial)result.put("assistant_conversation",labAssistantConversation.metadata().put("state",labAssistantConversationActive?"active":"cleared")
            .put("history_messages_applied",conversationHistory.length()));
        final CloudClient.Cancellation cancel = new CloudClient.Cancellation(); cloudCancellation = cancel;
        result.getJSONObject("voice_test").put("phase", "answering");
        final VoiceRoundJournal roundTiming=voiceRounds;
        // A round with nothing in it must not reach the model: carrying the previous exchange
        // as history, the model restates the last answer and the lens prints it twice.
        if(readingTrial&&VoicePhrase.isDegenerate(asr.getString("text"))){
            handoffEmptyUtterance(command,asr);return;
        }
        // the official assistant closes its dialog when the user says exit.
        // Ours sent every utterance to the model, which answered that it cannot do that.
        // Handled before any cloud call so no answer is generated for a close request.
        if(readingTrial&&VoiceExitPhrase.matches(asr.getString("text"))){
            handoffVoiceExit(command,asr);return;
        }
        if(readingTrial&&VoiceRecordingAction.matches(asr.getString("text"))){
            handoffVoiceRecording(command,session,asr);return;
        }
        final VoiceTodoAction todoAction=readingTrial?VoiceTodoAction.parse(asr.getString("text")):null;
        if(todoAction!=null){
            // ASR completed is dispatched on this Handler. Keep validation and the small
            // local transaction on that same thread: an exit cannot race a queued write.
            if(android.os.Looper.myLooper()!=handler.getLooper())throw new IllegalStateException("Todo action must run on session handler");
            if(!currentRound(command)||!session.equals(result.optString("session_id")))return;
            cancel.check();
            if(roundTiming!=null)roundTiming.mark(command,"answer_request_started",SystemClock.elapsedRealtime());
            JSONObject answer;
            try(TodoStore todos=new TodoStore(getApplicationContext())){
                answer=todoAction.execute(todos.voiceActions(),command,()->{
                    cancel.check();
                    if(!currentRound(command)||!session.equals(result.optString("session_id")))throw new InterruptedException("Stale todo round");
                });
            }
            if(roundTiming!=null)roundTiming.mark(command,"answer_returned",SystemClock.elapsedRealtime());
            JSONObject pipeline=new JSONObject().put("job_id",command).put("session_id",session).put("audio_source","glasses")
                .put("asr_mode","streaming").put("audio_saved",false).put("audio_uploaded",true).put("lens_verified",false)
                .put("asr",asr).put("answer",answer).put("provider","local_todo").put("text",answer.getString("text"))
                .put("status","completed").put("sample_rate",asr.getInt("sample_rate")).put("duration_ms",asr.getLong("audio_sent_ms"));
            // Action outcome is durable before display. Display failure is not DB rollback.
            writeVoiceTodoReceipt(pipeline);
            stage("local_todo_completed",answer.getJSONObject("action"));
            if(VoiceTodoSyncRound.eligible(answer))beginVoiceTodoSync(pipeline,command,session);
            else deliverVoiceTodo(pipeline,command);
            return;
        }
        final String selectedProvider=CloudClient.usesKnowledge(asr.getString("text"))?"knowledge":"deepseek";
        result.getJSONObject("voice_test").put("answer_provider",selectedProvider).put("query_started_elapsed_ms",SystemClock.elapsedRealtime());
        JSONObject waiting=new JSONObject().put("job_id",command).put("session_id",session).put("asr",asr)
            .put("provider",selectedProvider).put("status","pending").put("query_state","waiting_for_answer")
            .put("query_started_elapsed_ms",SystemClock.elapsedRealtime());
        result.put("glasses_cloud",waiting);writeCloudResult(waiting);stage("answer_waiting",selectedProvider);
        Runnable requestAnswer=()->voiceWorker.submit(() -> {
            JSONObject pipeline = new JSONObject();
            try {
                pipeline.put("provider",selectedProvider);
                pipeline.put("job_id", command).put("session_id", session).put("audio_source", "glasses")
                    .put("asr_mode", "streaming").put("audio_saved", false).put("audio_uploaded", true).put("lens_verified", false)
                .put("asr", asr).put("sample_rate", asr.getInt("sample_rate")).put("duration_ms", asr.getLong("audio_sent_ms"));
                cancel.check();
                if(roundTiming!=null)roundTiming.mark(command,"answer_request_started",SystemClock.elapsedRealtime());
                JSONObject answer=askWithOfficialRetry(pipeline,cancel,()->
                    readingTrial?CloudClient.askReadingTrial(config,asr.getString("text"),conversationHistory,cancel)
                        :CloudClient.ask(config, asr.getString("text"), cancel));
                if(roundTiming!=null)roundTiming.mark(command,"answer_returned",SystemClock.elapsedRealtime());
                pipeline.put("answer", answer).put("provider", answer.optString("provider")).put("text", answer.getString("text")).put("status", "completed");
            } catch (Exception e) { try {
                pipeline.put("status", "failed").put("error", e instanceof CloudClient.Failure ? e.getMessage() : e.getClass().getSimpleName());
            } catch (Exception ignored) {} }
            handler.post(() -> { try {
                if (!currentRound(command) || !session.equals(result.optString("session_id"))) return;
                publishVoiceTiming();
                // this notice used to be spelled "knowledge".equals(selectedProvider),
                // so a DeepSeek failure fell through to failStreaming, which sends type 7 and no
                // text. The user's account of that was "waited ten-odd seconds and it closed by
                // itself" -- the same thing a normal timed exit looks like. Every provider that
                // fails before an answer exists now says so on the lens; only the reachability of
                // the native page decides whether it can be said at all.
                boolean noticeable=!"completed".equals(pipeline.optString("status"))
                    &&labNativeMode()&&labNativeRound!=null;
                if(noticeable){
                    String notice=CloudFailureNotice.lensText(selectedProvider,pipeline.optString("error"));
                    pipeline.put("answer",new JSONObject().put("status","failed")
                        .put("provider","local_error_notice").put("text",notice));
                    pipeline.put("text",notice);
                }
                result.put("glasses_cloud", pipeline); writeCloudResult(pipeline);
                if (!"completed".equals(pipeline.optString("status"))) {
                    if(noticeable){
                        voiceArmed=false;cloudVoice=false;closeStreaming();
                        clearLabAssistantConversation("round_failed");
                        traceReading(command,"round_failed",new JSONObject().put("provider",selectedProvider)
                            .put("notice","requested").put("error",pipeline.optString("error")));
                        result.put("failure_notices_total",result.optLong("failure_notices_total")+1);
                        stage("cloud_failure_notice_requested",new JSONObject().put("command",command)
                            .put("provider",selectedProvider).put("error",pipeline.optString("error"))
                            .put("attempts",pipeline.optInt("answer_attempts",1)));
                        sendNativeAnswer(pipeline,command);
                    }else failStreaming(command,pipeline.optString("error"),true);
                    return;
                }
                result.getJSONObject("voice_test").put("phase", "sending_answer").put("audio_uploaded", true);
                result.put("text_send_completed", false); stage("glasses_cloud_completed", pipeline);
                sendNativeAnswer(pipeline, command);
            } catch (Exception e) { complete("failed", rootError(e)); } });
        });
        if(KnowledgeWaitingPreface.eligible(readingTrial,selectedProvider,labNativeRound))
            startKnowledgeWaitingPreface(command,asr.getString("text"),requestAnswer);
        else requestAnswer.run();
    }

    private void startKnowledgeWaitingPreface(String command,String query,Runnable requestAnswer)throws Exception{
        final NativeAnswerRound round=labNativeRound;final String request=KnowledgeWaitingPreface.request(round);
        final JSONObject receipt=KnowledgeWaitingPreface.receipt(command);
        result.put("knowledge_waiting_preface",receipt);sendContinuations.put(request,requestAnswer);
        try{
            sendBusiness("VOICE_ASSISTANT",32,KnowledgeWaitingPreface.payload(round,query,System.currentTimeMillis()),request);
            stage("knowledge_waiting_preface_requested",KnowledgeWaitingPreface.TEXT);
        }catch(Exception error){
            KnowledgeWaitingPreface.callback(receipt,false);sendContinuations.remove(request);
            stage("knowledge_waiting_preface_send_failed",error.getClass().getSimpleName());requestAnswer.run();return;
        }
        handler.postDelayed(()->{try{
            if(round!=labNativeRound||!currentRound(command)){sendContinuations.remove(request);return;}
            Runnable next=sendContinuations.remove(request);if(next==null)return;
            KnowledgeWaitingPreface.timeout(receipt);stage("knowledge_waiting_preface_callback_timeout",command);next.run();
        }catch(Exception error){complete("failed",rootError(error));}},3000);
    }

    private void writeVoiceTodoReceipt(JSONObject pipeline)throws Exception{
        String command=pipeline.getString("job_id");
        File dir=new File(getFilesDir(),"voice-actions");
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Todo receipt directory");
        pipeline.put("action_receipt_path","voice-actions/"+command+".json");
        File temp=new File(dir,command+".tmp"),target=new File(dir,command+".json");
        try(FileOutputStream out=new FileOutputStream(temp)){out.write(pipeline.toString(2).getBytes("UTF-8"));out.getFD().sync();}
        if(!temp.renameTo(target))throw new IOException("Todo action receipt replacement");
    }
    private void beginVoiceTodoSync(JSONObject pipeline,String command,String session)throws Exception{
        if(!currentRound(command)||!session.equals(result.optString("session_id")))return;
        try{
            if(!labNativeMode()||!connectionReady()||voiceRecording||voicePreparing||streamingAsr!=null||recordingPreparing
                ||!pipeline.getJSONObject("asr").optBoolean("capture_stop_send_confirmed_before_finish")
                ||(recorder!=null&&recorder.busy())||todoBusy())
                throw new IllegalStateException("Voice todo sync unavailable");
            JSONObject action=pipeline.getJSONObject("answer").getJSONObject("action");String localId=action.getString("task_id");
            boolean mapped=todoMapping(localId)!=null;
            if(action.getString("kind").equals("complete")&&!mapped){
                VoiceTodoSyncRound.annotate(pipeline,false,"not_mapped","","","no_glasses_identity");
            }else{
                todoSync=localTodoSync(command,mapped?"update_local":"push_local",localId);
                voiceTodoSync=new VoiceTodoSyncRound(command,session,pipeline,todoSync);
                result.getJSONObject("voice_test").put("phase","syncing_todo");
                result.put("glasses_cloud",pipeline);writeCloudResult(pipeline);
                advanceTodoSync();return;
            }
        }catch(Exception e){
            if(voiceOwnsTodoSync()&&voiceTodoSync.command.equals(command)){
                if(voiceTodoSync.handled)throw e;
                todoSync.fail(e.getClass().getSimpleName());publishTodoSync();return;
            }
            VoiceTodoSyncRound.annotate(pipeline,false,"not_started","","",e.getClass().getSimpleName());
        }
        writeVoiceTodoReceipt(pipeline);deliverVoiceTodo(pipeline,command);
    }
    private void deliverVoiceTodo(JSONObject pipeline,String command)throws Exception{
        if(!currentRound(command)||!pipeline.getString("session_id").equals(result.optString("session_id")))return;
        publishVoiceTiming();result.put("glasses_cloud",pipeline);writeCloudResult(pipeline);
        result.getJSONObject("voice_test").put("phase","sending_answer").put("audio_uploaded",true);
        result.put("text_send_completed",false);sendNativeAnswer(pipeline,command);
    }

    private VoiceRecordingAction voiceRecordingAction;
    /** End a round in which nothing was said, without calling the model and without printing
     * anything on the lens. See VoicePhrase.isDegenerate for what this prevents. */
    private void handoffEmptyUtterance(String command,JSONObject asr)throws Exception{
        if(android.os.Looper.myLooper()!=handler.getLooper())
            throw new IllegalStateException("Empty utterance must run on session handler");
        if(!currentRound(command))return;
        if(cloudCancellation!=null)cloudCancellation.cancel();
        closeStreaming();cloudVoice=false;
        labNativeContinuationCandidate=false;labNativeContinuationLinkType=0;
        labNativeContinuationUser="";labNativeContinuationDialog="";
        endVoiceRound(command,"empty_utterance");
        JSONObject host=result.optJSONObject("last_command");
        if(host!=null&&"pending".equals(host.optString("status")))
            host.put("status","completed").put("reason","empty_utterance");
        result.getJSONObject("voice_test").put("phase","empty_utterance").put("reason","nothing_was_said");
        stage("voice_empty_utterance",new JSONObject().put("command_id",command)
            .put("transcript",asr.optString("text")));
        if(!requestLabNativeReadingExit("auto_exit_requested")){
            sendBusiness("VOICE_ASSISTANT",7,new JSONObject().put("rc",1),"voice-exit-"+command);
            if(labNativeStandby&&!standbyReady)rearmStandby();
        }
    }
    /** close the answer page because the user asked for it, without calling the
     * model. The official assistant does this; ours sent the request to the model as chat and
     * relayed back that it has no such ability.
     *
     * <p>Reuses the auto_exit reason on purpose. handleLabNativeSend re-arms standby only for
     * that state, which is the compensation for firmware that closes the page without echoing
     * type 8; a private reason string here would leave the session unable to accept the next
     * wake, exactly the defect fixed earlier on 2026-09-20. */
    private void handoffVoiceExit(String command,JSONObject asr)throws Exception{
        if(android.os.Looper.myLooper()!=handler.getLooper())
            throw new IllegalStateException("Voice exit must run on session handler");
        if(!currentRound(command))return;
        if(cloudCancellation!=null)cloudCancellation.cancel();
        closeStreaming();cloudVoice=false;
        labNativeContinuationCandidate=false;labNativeContinuationLinkType=0;
        labNativeContinuationUser="";labNativeContinuationDialog="";
        endVoiceRound(command,"voice_exit_requested");
        JSONObject host=result.optJSONObject("last_command");
        if(host!=null&&"pending".equals(host.optString("status")))
            host.put("status","completed").put("reason","voice_exit_requested");
        result.getJSONObject("voice_test").put("phase","voice_exit").put("reason","user_asked_to_exit");
        stage("lab_native_voice_exit",new JSONObject().put("command_id",command)
            .put("transcript",asr.optString("text")));
        if(!requestLabNativeReadingExit("auto_exit_requested")){
            // No page is owned (the request arrived from a fresh wake rather than during a
            // follow-up window), so there is no exit send ACK to ride on: close and re-arm here.
            sendBusiness("VOICE_ASSISTANT",7,new JSONObject().put("rc",1),"voice-exit-"+command);
            if(labNativeStandby&&!standbyReady)rearmStandby();
        }
    }
    private void handoffVoiceRecording(String command,String session,JSONObject asr)throws Exception{
        if(android.os.Looper.myLooper()!=handler.getLooper())throw new IllegalStateException("Recording handoff must run on session handler");
        if(!currentRound(command)||!session.equals(result.optString("session_id")))return;
        if(!asr.optBoolean("capture_stop_send_confirmed_before_finish")||voiceRecording||voicePreparing)
            throw new IOException("Assistant microphone stop not confirmed");
        if(!connectionReady()||recordingPreparing||(recorder!=null&&recorder.busy()))throw new IOException("Recorder not ready");
        String recordCommand=UUID.randomUUID().toString();
        VoiceRecordingAction action=new VoiceRecordingAction(session,command,recordCommand,asr.getString("text"));
        // The final transcript requested a handoff, not a successful recording yet.
        result.put("voice_recording_asr",asr);
        endVoiceRound(command,"recording_handoff");
        if(labNativeRound!=null&&labNativeRound.same(command,session))labNativeRound.finishOnce();
        stopStandby();resetLabNativeRound();nativeReply=false;
        voiceRecordingAction=action;activeCommand=recordCommand;activeCommandKind="record-start";
        result.put("last_command",new JSONObject().put("id",recordCommand).put("kind","record-start")
            .put("source","voice").put("voice_command_id",command).put("status","pending").put("started_ms",SystemClock.elapsedRealtime()));
        result.getJSONObject("voice_test").put("phase","handed_to_recording");
        try{
            publishVoiceRecording(new JSONObject().put("phase","connecting"));
            prepareRecordingTask();
        }catch(Exception e){failRecordingPreparation();stage("voice_recording_start_failed",e.getClass().getSimpleName());}
    }
    private void publishVoiceRecording(JSONObject recording)throws Exception{
        VoiceRecordingAction action=voiceRecordingAction;
        if(action==null||!action.session.equals(result.optString("session_id"))||!action.observe(activeCommand,recording))return;
        JSONObject snapshot=action.snapshot();result.put("voice_recording",snapshot);
        try{
        File folder=new File(getFilesDir(),"voice-record-actions");
        if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Recording action receipt directory");
        File temp=new File(folder,action.voiceCommand+".tmp"),file=new File(folder,action.voiceCommand+".json");
        try(FileOutputStream out=new FileOutputStream(temp)){out.write(snapshot.toString(2).getBytes("UTF-8"));out.getFD().sync();}
        if(!temp.renameTo(file))throw new IOException("Recording action receipt rename");
        result.put("voice_recording_receipt_path","voice-record-actions/"+action.voiceCommand+".json");
        result.remove("voice_recording_receipt_error");
        }catch(Exception e){result.put("voice_recording_receipt_error",e.getClass().getSimpleName());}
        stage("voice_recording_state",snapshot);
    }

    private void runGlassesCloud(String command) throws Exception {
        final List<byte[]> packets = new ArrayList<>(voiceAudio); voiceAudio.clear(); cloudVoice = false;
        final JSONObject config = CloudConfig.load(this);
        final String session = result.getString("session_id");
        final boolean useNativePage = nativeReply;
        final CloudClient.Cancellation cancel = new CloudClient.Cancellation(); cloudCancellation = cancel;
        result.getJSONObject("voice_test").put("phase", "decoding"); stage("glasses_decode_started", packets.size());
        final VoiceRoundJournal roundTiming=voiceRounds;
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
                if(roundTiming!=null)roundTiming.mark(command,"batch_asr_request_started",SystemClock.elapsedRealtime());
                JSONObject asr = CloudClient.transcribe(config, wav, "audio/wav", cancel);
                if(roundTiming!=null)roundTiming.mark(command,"asr_final_text",SystemClock.elapsedRealtime());
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
                if(roundTiming!=null)roundTiming.mark(command,"answer_request_started",SystemClock.elapsedRealtime());
                JSONObject answer = askWithOfficialRetry(pipeline, cancel,
                    () -> CloudClient.ask(config, asr.getString("text"), cancel));
                if(roundTiming!=null)roundTiming.mark(command,"answer_returned",SystemClock.elapsedRealtime());
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
                    result.getJSONObject("last_command").put("status", "failed"); endVoiceRound(command,"failed"); stage("glasses_cloud_failed", pipeline); rearmStandby(); return;
                }
                result.getJSONObject("voice_test").put("phase", "sending_answer");
                customNotification = new JSONObject().put("title", "眼镜语音问答").put("content", pipeline.getJSONObject("answer").optString("lens_text",pipeline.getString("text")));
                validateNotification(customNotification); textAttempted = false; result.put("text_send_completed", false);
                result.remove("notification_reply"); stage("glasses_cloud_completed", pipeline);
                if (useNativePage) sendNativeAnswer(pipeline, command); else sendTestText();
            } catch (Exception e) { complete("failed", rootError(e)); } });
        });
    }

    /** One answer request, repeated once if it went unanswered -- the official app's own policy
     * (see {@link CloudRetry} for the captured values it copies).
     *
     * <p>Runs on the voice worker, never the main thread: the wait between attempts is a real
     * sleep. Cancellation is re-checked after it, so a round the user has already abandoned does
     * not come back to life five seconds later. */
    private JSONObject askWithOfficialRetry(JSONObject pipeline, CloudClient.Cancellation cancel,
                                            java.util.concurrent.Callable<JSONObject> request) throws Exception {
        Exception failure = null;
        for (int attempt = 1; attempt <= CloudRetry.MAX_ATTEMPTS; attempt++) {
            try {
                JSONObject answer = request.call();
                pipeline.put("answer_attempts", attempt);
                return answer;
            } catch (Exception e) {
                failure = e;
                String error = e instanceof CloudClient.Failure ? e.getMessage() : e.getClass().getSimpleName();
                pipeline.put("answer_attempts", attempt);
                if (!CloudRetry.again(attempt, error)) break;
                pipeline.put("answer_retried_after", error);
                Thread.sleep(CloudRetry.DELAY_MS);
                cancel.check();
            }
        }
        throw failure;
    }
    private void sendNativeAnswer(JSONObject pipeline, String command) throws Exception {
        String text = pipeline.getJSONObject("answer").optString("lens_text",pipeline.getString("text")), query = pipeline.getJSONObject("asr").getString("text");
        if(labNativeMode()&&text.trim().isEmpty()){failStreaming(command,"回答没有可显示文字",true);return;}
        List<String> chunks = new ArrayList<>(); StringBuilder chunk = new StringBuilder(); int size = 0;
        for (int point : text.codePoints().toArray()) {
            String value = new String(Character.toChars(point)); int count = value.getBytes("UTF-8").length;
            if (size + count > 480) { chunks.add(chunk.toString()); chunk.setLength(0); size = 0; }
            chunk.append(value); size += count;
        }
        if (chunk.length() > 0) chunks.add(chunk.toString());
        labNativeAnswerChunks=Math.max(1,chunks.size());
        // Long and short answers now exit the same way. Classifying by code points was a proxy for
        // "has the user finished reading", and an unreliable one; the glasses report display
        // completion directly via assistant_type_11, so the proxy is gone. The official app was
        // measured on 2026-09-20 doing the same: no length classification, one window.
        result.put("submitted_text", new JSONObject().put("title", "眼镜原生语音回答").put("content", text).put("uid", command));
        notificationUid = command;
        sendNativeChunk(chunks, 0, query, command);
    }
    private void sendNativeChunk(List<String> chunks, int i, String query, String command) throws Exception {
        if (finished || finishing || !command.equals(activeCommand)) return;
        final NativeAnswerRound nativeRound=labNativeMode()?labNativeRound:null;
        if(labNativeMode()&&(nativeRound==null||nativeRound.terminal()||!nativeRound.same(command,result.optString("session_id"))))return;
        if (i < chunks.size()) {
            if(i==0)voiceTiming(command,"answer_first_send_requested",SystemClock.elapsedRealtime());
            JSONObject answer = nativeRound==null?nativeAnswerPayload(chunks.get(i),query,command)
                :nativeRound.payload(chunks.get(i),query,i==chunks.size()-1,System.currentTimeMillis());
            String id = nativeRound==null?"native-answer-" + command + "-" + i:nativeRound.request("answer")+"-"+i;
            sendContinuations.put(id, () -> { try { sendNativeChunk(chunks, i + 1, query, command); } catch (Exception e) { complete("failed", rootError(e)); } });
            sendBusiness("VOICE_ASSISTANT", 32, answer, id);
            return;
        }
        voiceTiming(command,"answer_chunks_send_completed",SystemClock.elapsedRealtime());
        // Same window as the configured auto-exit: the literal 15000 predated that setting
        // and silently ignored it. Default is 15s, so behaviour is unchanged.
        nativeDisplayUntil = SystemClock.elapsedRealtime() + labNativeAutoExitSeconds * 1000L;
        if(nativeRound!=null){
            if(nativeRound.requiresFinish())sendBusiness("VOICE_ASSISTANT",12,new JSONObject(),nativeRound.request("finish"));
            else completeLabNativeDelivery(nativeRound,true);
            return;
        }
        sendBusiness("VOICE_ASSISTANT", 12, new JSONObject(), command);
        handler.postDelayed(() -> { try {
            if (!finished && !finishing && command.equals(activeCommand) && !voiceRecording)
                sendBusiness("VOICE_ASSISTANT", 7, new JSONObject().put("rc", 1), "voice-exit-" + command);
        } catch (Exception e) { complete("failed", rootError(e)); } }, labNativeAutoExitSeconds * 1000L);
    }

    /** Starts the reading and follow-up windows. Called when the glasses report the answer has
     * finished scrolling (assistant_type_11), or by the fallback when that report never arrives.
     * The official app starts its single 10s timer at the same point. */
    private void startReadingWindows() throws Exception {
        if(!awaitingDisplayComplete)return;
        awaitingDisplayComplete=false;
        if(displayCompleteFallback!=null){handler.removeCallbacks(displayCompleteFallback);displayCompleteFallback=null;}
        final NativeAnswerRound reading=labNativeRound;
        if(reading==null)return;
        long now=SystemClock.elapsedRealtime();
        nativeFollowupUntil=now+labNativeFollowupSeconds*1000L;
        nativeDisplayUntil=now+labNativeAutoExitSeconds*1000L;
        if(labNativeAutoExit!=null)handler.removeCallbacks(labNativeAutoExit);
        labNativeAutoExit=()->{try{
            if(labNativeRound==reading&&!finished&&!finishing){
                traceReading(reading.command,"auto_exit_timer_fired",null);
                requestLabNativeReadingExit("auto_exit_requested");
            }
        }catch(Exception e){try{stage("lab_native_auto_exit_failed",rootError(e));}catch(Exception ignored){}}};
        handler.postDelayed(labNativeAutoExit,labNativeAutoExitSeconds*1000L);
        // The receipt must show the real deadlines once they exist, otherwise a reader cannot tell
        // a round still waiting to be displayed from one whose windows are already running.
        JSONObject receipt=result.optJSONObject("lab_native_reading");
        if(receipt!=null&&reading.command.equals(receipt.optString("command_id")))
            receipt.put("awaiting_display_complete",false)
                .put("followup_due_elapsed_ms",nativeFollowupUntil)
                .put("auto_exit_due_elapsed_ms",nativeDisplayUntil);
        stage("lab_native_reading_windows_started",new JSONObject()
            .put("command_id",reading.command).put("auto_exit_seconds",labNativeAutoExitSeconds)
            .put("followup_seconds",labNativeFollowupSeconds));
    }
    private void completeLabNativeDelivery(NativeAnswerRound round,boolean success)throws Exception{
        if(labNativeRound!=round||!currentRound(round.command)||!round.finishOnce())return;
        JSONObject cloud=result.getJSONObject("glasses_cloud");
        boolean failedAnswer="failed".equals(cloud.optString("status"));
        result.put("text_send_completed",success);
        result.getJSONObject("last_command").put("status",success&&!failedAnswer?"completed":"failed");
        if(success){
            if(labNativeAutoExit!=null)handler.removeCallbacks(labNativeAutoExit);
            // Do not start the windows from this callback: it only means the phone finished
            // sending, while the glasses are still scrolling the answer. Wait for the glasses to
            // report assistant_type_11, whose delay tracks content length (measured 0.75 / 9.77 /
            // 16.99 / 15.46s). Starting here cut long answers short mid-scroll.
            awaitingDisplayComplete=true;
            nativeFollowupUntil=0;nativeDisplayUntil=0;
            // type 11 may never arrive. Never wait unbounded: an unclosed wait blocks the round
            // the same way an unclosed command blocks notifications.
            if(displayCompleteFallback!=null)handler.removeCallbacks(displayCompleteFallback);
            displayCompleteFallback=()->{try{
                if(awaitingDisplayComplete&&labNativeRound==round&&!finished&&!finishing){
                    stage("lab_native_display_complete_timeout",labNativeDisplayWaitSeconds);
                    startReadingWindows();
                }
            }catch(Exception e){try{stage("lab_native_display_wait_failed",rootError(e));}catch(Exception ignored){}}};
            handler.postDelayed(displayCompleteFallback,displayWaitMs(labNativeAnswerChunks));
            long now=SystemClock.elapsedRealtime();
            result.put("lab_native_reading",new JSONObject().put("command_id",round.command).put("session_id",round.session)
                .put("state","awaiting_user_exit").put("auto_exit_seconds",labNativeAutoExitSeconds)
                .put("followup_seconds",labNativeFollowupSeconds)
                .put("awaiting_display_complete",true)
                .put("display_wait_seconds",labNativeDisplayWaitSeconds)
                .put("followup_due_elapsed_ms",JSONObject.NULL)
                .put("auto_exit_due_elapsed_ms",JSONObject.NULL)
                .put("exit_policy","bounded_auto_exit")
                .put("lens_verified",false)
                .put("association","current_local_context_not_device_page_id"));
            traceReading(round.command,"answer_send_completed",new JSONObject()
                .put("auto_exit_due_elapsed_ms",nativeDisplayUntil));
            // The auto-exit timer is armed by startReadingWindows() once the glasses report the
            // scroll finished, not here: arming it at send time is what cut long answers short.
        }
        if(success&&!failedAnswer&&labAssistantConversationActive){
            JSONObject asr=cloud.optJSONObject("asr");
            String query=asr==null?"":asr.optString("text"),answer=cloud.optString("text");
            labAssistantConversation.commit(query,answer);
            JSONObject context=labAssistantConversation.metadata().put("state","active").put("last_event","answer_send_completed")
                .put("command_id",round.command);
            cloud.put("conversation_context",context);result.put("assistant_conversation",context);
        }
        cloud.put("delivery",new JSONObject().put("command_id",round.command).put("session_id",round.session)
            .put("notification_uid",notificationUid).put("status",success?"completed":"failed").put("lens_verified",false));
        result.getJSONObject("voice_test").put("phase",success?(failedAnswer?"failed_displayed":"finished"):"failed");writeCloudResult(cloud);
        if(success)voiceTiming(round.command,"answer_end_send_completed",SystemClock.elapsedRealtime());
        endVoiceRound(round.command,success&&!failedAnswer?"answer_sent":"failed");
        stage("lab_native_delivery",success);
        if(success)armLabNativeFollowup();
    }
    private boolean handleLabNativeReadingExit(int type)throws Exception{
        NativeAnswerRound round=labNativeRound;
        if(type!=8||!labNativeReadingOwned())return false;
        if(labNativeAutoExit!=null){handler.removeCallbacks(labNativeAutoExit);labNativeAutoExit=null;}
        if(labNativeExitConfirmation!=null){handler.removeCallbacks(labNativeExitConfirmation);labNativeExitConfirmation=null;}
        nativeDisplayUntil=0;nativeFollowupUntil=0;
        cancelDisplayCompleteWait("device_exit_received");
        clearLabAssistantConversation("device_exit_received");
        result.getJSONObject("lab_native_reading").put("state","device_exit_received");
        traceReading(round.command,"device_type8_received",new JSONObject()
            .put("association","current_local_context_not_device_page_id"));
        stage("lab_native_reading_exit",new JSONObject().put("command_id",round.command).put("association","current_local_context_not_device_page_id"));
        // A device exit can land while a type-11 continuation candidate is still listening. The
        // candidate owns the pending host command, and the no-speech timer that would close it is
        // keyed on voiceCommand, which rearmStandby() replaces -- so nothing ever closes it and
        // every later wake is refused with lab_standby_wake_busy. Observed
        // 2026-09-20: device exit 4s into the window, then no wake accepted for the rest of the
        // session.
        if(labNativeContinuationCandidate){
            labNativeContinuationCandidate=false;labNativeContinuationLinkType=0;
            labNativeContinuationUser="";labNativeContinuationDialog="";
            stopStreamingCapture(activeCommand);closeStreaming();cloudVoice=false;
            JSONObject inflight=result.optJSONObject("last_command");
            if(inflight!=null&&"pending".equals(inflight.optString("status")))
                inflight.put("status","completed").put("reason","device_exit_during_continuation");
            stage("lab_native_continuation_cancelled_by_device_exit",round.command);
        }
        if(labNativeStandby&&!standbyReady)rearmStandby();
        return true;
    }
    private boolean labNativeReadingOwned(){
        NativeAnswerRound round=labNativeRound;JSONObject reading=result.optJSONObject("lab_native_reading");
        return getPackageName().equals("dev.xr.rayneo.sdklab")&&round!=null&&round.terminal()
            &&round.session.equals(result.optString("session_id"))&&reading!=null
            &&round.command.equals(reading.optString("command_id"))&&!"device_exit_received".equals(reading.optString("state"));
    }
    private boolean labNativeReadingActive(){
        JSONObject reading=result.optJSONObject("lab_native_reading");
        return labNativeReadingOwned()&&"awaiting_user_exit".equals(reading.optString("state"));
    }
    /** Stops waiting for the display-complete receipt, because the round it belonged to is over.
     *
     * <p>Measured 2026-09-21 in session {@code 38cb31e9}: the answer was delivered at +387.4s, the
     * glasses reported their exit at +392.7s, and the fallback still fired at +447.4s and called
     * {@code startReadingWindows} for that dead round -- pushing {@code nativeDisplayUntil} 15
     * seconds into the future. For those 15 seconds the app believed an answer was on the lens
     * while the lens was blank, and every phone notification was refused as
     * {@code lens_showing_answer}.
     *
     * <p>An unfinished wait blocks unrelated notifications, and the comment above the fallback already said so:
     * "an unclosed wait blocks the round the same way an unclosed command blocks notifications".
     * The wait was bounded; what was missing is that ending the round also ends the wait. */
    private void cancelDisplayCompleteWait(String reason){
        if(displayCompleteFallback!=null){handler.removeCallbacks(displayCompleteFallback);displayCompleteFallback=null;}
        if(!awaitingDisplayComplete)return;
        awaitingDisplayComplete=false;
        // Observation only; never let a failed diagnostic write change whether the wait was cancelled.
        try{stage("lab_native_display_wait_cancelled",reason);}catch(Exception ignored){}
    }
    private boolean requestLabNativeReadingExit()throws Exception{
        return requestLabNativeReadingExit("host_exit_requested");
    }
    private boolean requestLabNativeReadingExit(String requestedState)throws Exception{
        if(!labNativeReadingActive())return false;
        if(labNativeAutoExit!=null){handler.removeCallbacks(labNativeAutoExit);labNativeAutoExit=null;}
        nativeDisplayUntil=0;nativeFollowupUntil=0;result.getJSONObject("lab_native_reading").put("state",requestedState);
        cancelDisplayCompleteWait(requestedState);
        clearLabAssistantConversation(requestedState);
        traceReading(labNativeRound.command,"type7_requested",new JSONObject().put("reason",requestedState));
        sendBusiness("VOICE_ASSISTANT",7,new JSONObject().put("rc",1),labNativeRound.request("exit"));
        stage("lab_native_reading_exit_requested",new JSONObject().put("command_id",labNativeRound.command).put("reason",requestedState));return true;
    }
    private boolean handleLabNativeSend(String id,String error)throws Exception{
        if(!id.startsWith("lab-native-"))return false;
        if(id.startsWith("lab-native-exit-"))
            traceReading(id.substring("lab-native-exit-".length()),"type7_send_completed",
                new JSONObject().put("success",error==null).put("reason","round_failed"));
        NativeAnswerRound round=labNativeRound;
        if(round!=null&&round.session.equals(result.optString("session_id"))&&id.equals(round.request("exit"))){
            traceReading(round.command,"type7_send_completed",new JSONObject().put("success",error==null));
            result.put("lab_native_exit_send_completed",error==null);
            JSONObject reading=result.optJSONObject("lab_native_reading");
            String state=reading==null?"":reading.optString("state");
            if(reading!=null&&round.command.equals(reading.optString("command_id"))&&state.endsWith("_requested")){
                String prefix=state.substring(0,state.length()-"_requested".length());
                reading.put("state",prefix+(error==null?"_send_completed":"_send_failed"));
                JSONObject cloud=result.optJSONObject("glasses_cloud");
                if(cloud!=null){cloud.put("exit",new JSONObject().put("reason",prefix).put("status",error==null?"completed":"failed"));writeCloudResult(cloud);}
                if(prefix.equals("auto_exit")&&labNativeStandby&&!standbyReady){
                    // Re-arm even when the exit frame failed to send. The round is over locally
                    // either way, and leaving voiceArmed false drops every later wake silently --
                    // without even a lab_standby_wake_busy to show why.
                    rearmStandby();
                }
                if(error==null&&prefix.equals("auto_exit")){
                    // The firmware can accept a new wake almost immediately after closing the
                    // answer while omitting the type-8 exit echo. Re-arm on the send ACK. A later
                    // identity-less type-8 cannot be reliably assigned to the old or new round
                    // once a new wake has reset the local reading state (see the G0 fixture).
                    labNativeExitConfirmation=()->{try{
                        JSONObject current=result.optJSONObject("lab_native_reading");
                        if(labNativeRound==round&&current!=null&&"auto_exit_send_completed".equals(current.optString("state"))){
                            current.put("state","auto_exit_device_exit_unconfirmed");
                            stage("lab_native_auto_exit_unconfirmed",round.command);
                        }
                    }catch(Exception ignored){}};
                    handler.postDelayed(labNativeExitConfirmation,5000);
                }
            }
            stage("lab_native_exit_send_callback",new JSONObject().put("command_id",round.command).put("success",error==null));
            return true;
        }
        if(round==null&&labNativeMode()&&id.equals("lab-native-exit-"+activeCommand)){
            result.put("lab_native_exit_send_completed",error==null);stage("lab_native_exit_send_callback",error==null);return true;
        }
        if(round==null||!round.same(activeCommand,result.optString("session_id"))||!round.owns(id)){
            sendContinuations.remove(id);return true;
        }
        if(!currentRound(round.command)||round.terminal()){sendContinuations.remove(id);return true;}
        if(id.equals(round.request("answer")+"-preface")){
            JSONObject preface=result.optJSONObject("knowledge_waiting_preface");
            KnowledgeWaitingPreface.callback(preface,error==null);
            stage("knowledge_waiting_preface_callback",new JSONObject().put("command_id",round.command).put("success",error==null));
            Runnable next=sendContinuations.remove(id);if(next!=null)next.run();return true;
        }
        if(id.equals(round.request("stop"))){
            result.getJSONObject("voice_test").put("stop_send_completed",error==null);
            voiceTiming(round.command,"capture_stop_send_completed",SystemClock.elapsedRealtime());
            StreamingAsr stopped=labNativeStopStream;if(stopped!=null)stopped.acknowledgeCaptureStop(error==null);
            stage("lab_native_stop_send_callback",error==null);return true;
        }
        if(error!=null){sendContinuations.remove(id);failStreaming(round.command,"Native answer send failed: "+error,true);return true;}
        if(id.equals(round.request("finish"))){completeLabNativeDelivery(round,true);return true;}
        Runnable next=sendContinuations.remove(id);if(next!=null)next.run();
        stage("lab_native_answer_chunk_callback",id);return true;
    }

    private void refreshBondState() throws Exception {
        result.put("system_bond_state", manager.getAdapter().getRemoteDevice(address).getBondState());
        result.put("system_bond_checked_at_ms",System.currentTimeMillis());
        if(result.optInt("system_bond_state")!=BluetoothDevice.BOND_BONDED){
            result.put("sdk_bond_success",false);bondEvidenceRequired=true;
        }
        boolean ready=connectionReady();
        result.put("connection_ready",ready);
        ConnectionService.connectionReady(ready);
        if(!ready&&standbyEnabled)stopStandby();
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
        if(businessName.equals("LAUNCHER")&&json.optString("cmd").equals("sync_time")){
            if(type!=5||!clockPending||!id.equals(clockRequest)||clockPayload==null||!json.toString().equals(clockPayload.toString()))throw new IllegalArgumentException("Unowned phone clock update");
        }
        if(businessName.equals("LAUNCHER")&&type==18&&dashboardQuery!=null&&!dashboardQuery.done
           &&id.equals("dashboard-query-"+dashboardQuery.id)){
            // Read-only A2UI baseline: body is pinned so a non-answer stays interpretable.
            if(!getPackageName().equals("dev.xr.rayneo.sdklab")
               ||!json.toString().equals(new JSONObject().put("cmd",LabDashboardQuery.CMD)
                   .put("payload",new JSONObject().put("version",1).put("value",0)).toString()))
                throw new IllegalArgumentException("Unowned dashboard query");
        } else if(businessName.equals("LAUNCHER")&&type==18&&widgetCommand!=null&&!widgetCommand.done
           &&id.equals("widget-"+widgetCommand.id)){
            // A2UI card write: the whole body must be the pinned one for our own card id.
            if(!getPackageName().equals("dev.xr.rayneo.sdklab")
               ||!json.toString().equals(widgetBody(widgetCommand.install).toString()))
                throw new IllegalArgumentException("Unowned widget command");
        } else if(businessName.equals("LAUNCHER")&&type==18){
            if(!getPackageName().equals("dev.xr.rayneo.sdklab")||weatherUpdate==null||weatherUpdate.done||!id.equals("weather-update-"+weatherUpdate.id)||!json.toString().equals(weatherUpdate.payload.toString()))throw new IllegalArgumentException("Unowned weather command");
        }
        result.put("business_submit_total", result.optLong("business_submit_total") + 1);
        if (businessName.equals("VOICE_ASSISTANT") && type == 2 && json.optInt("rc") == 1)
            result.put("recorder_start_requests_total", result.optLong("recorder_start_requests_total") + 1);
        Class<?> businessType = runtime.type("S3.h"), messageType = runtime.type("I3.P");
        Object business = businessType.getMethod("valueOf", String.class).invoke(null, businessName);
        Object priority = runtime.type("I3.b").getField("d").get(null);
        // Match the iOS recorder/exit fixtures, including their explicit empty field 4.
        byte[] payload = BusinessEnvelope.encode(type, json.toString(), businessName.equals("VOICE_ASSISTANT") && (type == 2 || type == 7 || type == 12));
        if(businessName.equals("SCHEDULE_TODO")){
            boolean readOnly=todoQuery!=null&&!todoQuery.done&&type==15&&todoQuery.owns(id)&&json.toString().equals(LabTodoQuery.request().toString());
            boolean syncAllowed=todoSync!=null&&!todoSync.done&&todoSync.id.equals(activeCommand)
                &&((type==15&&id.equals(todoSync.readId())&&todoSync.readSent&&json.toString().equals(LabTodoQuery.request().toString()))
                    ||(type==2&&id.equals(todoSync.writeId())&&todoSync.writeSent&&todoSync.phase.equals("writing")&&json.toString().equals(todoSync.payload.toString())));
            TodoFullSync full=todoFullSync;
            boolean fullAllowed=full!=null&&!full.done&&full.id.equals(activeCommand)
                &&((type==15&&id.equals(full.readId())&&full.readSent&&json.toString().equals(LabTodoQuery.request().toString()))
                    ||(type==6&&id.equals(full.tableId())&&full.tableSent&&full.phase.equals("writing_table")&&json.toString().equals(full.table.toString()))
                    ||(type==7&&id.equals(full.deleteId())&&full.deleteSent&&full.phase.equals("writing_delete")&&full.deleteCommand!=null&&json.toString().equals(full.deleteCommand.toString())));
            if(!getPackageName().equals("dev.xr.rayneo.sdklab")||(!readOnly&&!syncAllowed&&!fullAllowed))throw new IllegalArgumentException("Unowned todo command");
        }
        if(businessName.equals("MARS_FOTA")){
            if(!getPackageName().equals("dev.xr.rayneo.sdklab")||firmwareQuery==null||firmwareQuery.done||type!=1||json.length()!=0||!id.equals("firmware-query-"+firmwareQuery.id))throw new IllegalArgumentException("Only bounded read-only firmware query allowed");
            payload=BusinessEnvelope.encode(1,"");
        }
        Object message = messageType.getConstructor(byte[].class, String.class, String.class, businessType,
                runtime.type("S3.Q"), runtime.type("I3.b"), boolean.class, boolean.class,
                runtime.type("T3.p"), runtime.type("kotlin.jvm.functions.Function2"))
            .newInstance(payload, id, field(targetDevice, "a"), business, null, priority, false, !businessName.equals("VOICE_ASSISTANT")&&!businessName.equals("RECORDING_SERVICE")&&!businessName.equals("AI_SUBTITLE"), null, null);
        Class<?> callbackType = runtime.type("kotlin.jvm.functions.Function2");
        Object callback = Proxy.newProxyInstance(runtime.loader, new Class<?>[]{callbackType}, (proxy, method, args) -> {
            if (method.getName().equals("invoke")) {
                String error = args[1] instanceof Enum ? ((Enum<?>)args[1]).name() : args[1] == null ? null : "unknown";
                handler.post(() -> {
                        if (finished || finishing) return;
                        try{if(handleLabNativeSend(id,error))return;}catch(Exception e){complete("failed",rootError(e));return;}
                        if(id.startsWith("lab-asr-")){
                            if(asrTrial!=null&&asrTrial.owns(id))try{
                                LabAsrTrial ownedTrial=asrTrial;StreamingAsr ownedStream=labAsrStream;
                                int nextActions=ownedTrial.sent(id,error==null,SystemClock.elapsedRealtime());
                                if(id.equals(ownedTrial.request(LabAsrTrial.STOP))&&ownedStream!=null)
                                    ownedStream.acknowledgeCaptureStop(error==null);
                                applyAsrActions(ownedTrial,nextActions);
                            }
                                catch(Exception e){complete("failed",rootError(e));}
                            return;
                        }
                        if(wakeTrial!=null&&wakeTrial.owns(id)){
                            try{wakeTrial.sent(id,error==null,SystemClock.elapsedRealtime());publishWakeTrial();}
                            catch(Exception e){complete("failed",rootError(e));}return;
                        }
                        if(headTrial!=null&&headTrial.owns(id)){
                            try{headTrial.sent(id,error==null,SystemClock.elapsedRealtime());publishHeadTrial();}
                            catch(Exception e){complete("failed",rootError(e));}return;
                        }
                        if(crownTrial!=null&&crownTrial.owns(id)){
                            try{crownTrial.sent(id,error==null,SystemClock.elapsedRealtime());publishCrownTrial();}
                            catch(Exception e){complete("failed",rootError(e));}return;
                        }
                        if(autoLockTrial!=null&&autoLockTrial.owns(id)){
                            try{autoLockTrial.sent(id,error==null,SystemClock.elapsedRealtime());publishAutoLockTrial();}
                            catch(Exception e){complete("failed",rootError(e));}return;
                        }
                        if(id.startsWith("settings-query-")){
                            if(settingsQuery==null||settingsQuery.done||!id.equals("settings-query-"+settingsQuery.id))return;
                            try{settingsQuery.sent(error==null,SystemClock.elapsedRealtime());publishSettingsQuery();stage("settings_query_send_completed",error==null);}catch(Exception e){complete("failed",rootError(e));}return;
                        }
                        if(id.startsWith("dashboard-query-")){
                            if(dashboardQuery==null||dashboardQuery.done||!id.equals("dashboard-query-"+dashboardQuery.id))return;
                            try{dashboardQuery.sent(error==null,SystemClock.elapsedRealtime());publishDashboardQuery();stage("dashboard_query_send_completed",error==null);}catch(Exception e){complete("failed",rootError(e));}
                        }
                        if(id.startsWith("widget-")){
                            if(widgetCommand==null||widgetCommand.done||!id.equals("widget-"+widgetCommand.id))return;
                            try{widgetCommand.sent(error==null,SystemClock.elapsedRealtime());publishWidgetCommand();stage("widget_command_send_completed",error==null);}catch(Exception e){complete("failed",rootError(e));}
                        }
                        if(id.startsWith("todo-full-")){
                            if(todoFullSync==null||todoFullSync.done||!todoFullSync.owns(id))return;
                            try{todoFullSync.sent(this::reconcileTodos,id,error==null,SystemClock.elapsedRealtime());advanceTodoFullSync();}
                            catch(Exception e){failTodoFullSync(e);}return;
                        }
                        if(id.startsWith("todo-sync-")){
                            if(todoSync==null||todoSync.done||!todoSync.owns(id))return;
                            try{todoSync.sent(id,error==null,SystemClock.elapsedRealtime());advanceTodoSync();}
                            catch(Exception e){try{todoSync.fail(e.getClass().getSimpleName());publishTodoSync();}catch(Exception failure){complete("failed",rootError(failure));}}return;
                        }
                        if(id.startsWith("todo-query-")){
                            if(todoQuery==null||!todoQuery.owns(id))return;
                            try{todoQuery.sent(error==null,SystemClock.elapsedRealtime());publishTodoQuery();}
                            catch(Exception e){complete("failed",rootError(e));}return;
                        }
                        if(id.startsWith("phone-clock-")){
                            if(!clockPending||!id.equals(clockRequest))return;
                            clockPending=false;
                            if(error!=null)clockBasis="";
                            try{result.getJSONObject("phone_clock_sync").put("phase",error==null?"transport_sent":"send_failed").put("send_ack",error==null);stage("phone_clock_state",result.getJSONObject("phone_clock_sync"));}
                            catch(Exception e){android.util.Log.e("SdkProbe","phone_clock_receipt_failed");}return;
                        }
                        if(id.startsWith("weather-update-")){
                            if(weatherUpdate==null||weatherUpdate.done||!id.equals("weather-update-"+weatherUpdate.id))return;
                            try{weatherUpdate.sent(error==null,SystemClock.elapsedRealtime());publishWeatherUpdate();}
                            catch(Exception e){android.util.Log.e("SdkProbe","weather_receipt_failed: "+e.getClass().getSimpleName());}return;
                        }
                        if(id.startsWith("firmware-query-")){
                            if(firmwareQuery==null||!id.equals("firmware-query-"+firmwareQuery.id))return;
                            try{firmwareQuery.sent(error==null,SystemClock.elapsedRealtime());publishFirmwareQuery();stage("firmware_query_send_ack",error==null);}
                            catch(Exception e){complete("failed",rootError(e));}return;
                        }
                        if(id.startsWith("answer-trial-")){
                            if(answerTrial==null)return;
                            for(int action=1;action<=4;action++)if(id.equals("answer-trial-"+action+"-"+answerTrial.id)){
                                try{stage("answer_trial_callback",new JSONObject().put("action",action).put("success",error==null));advanceAnswerTrial(answerTrial.sent(action,error==null,SystemClock.elapsedRealtime()));}
                                catch(Exception e){complete("failed",rootError(e));}
                                break;
                            }
                            return;
                        }
                        if (id.startsWith("display-")) {
                            if (displayTrial == null) return;
                            for (String part : new String[]{"config", "text", "exit"}) {
                                if (id.equals("display-" + part + "-" + displayTrial.id)) {
                                    try {
                                        stage("display_send_callback", new JSONObject().put("part", part).put("success", error == null));
                                        advanceDisplayTrial(displayTrial.sent(part, error == null, SystemClock.elapsedRealtime()));
                                        publishDisplayTrial();
                                    } catch (Exception e) { complete("failed", rootError(e)); }
                                    return;
                                }
                            }
                            return;
                        }
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
                        if(voiceCommand!=null&&id.equals("voice-start-"+voiceCommand)&&error==null)
                            voiceTiming(voiceCommand,"capture_send_completed",SystemClock.elapsedRealtime());
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
                        if (id.equals(activeCommand) && "setup-finish".equals(activeCommandKind)) {
                            result.put("setup_finish_sent", error == null);
                            result.getJSONObject("last_command").put("status", error == null ? "completed" : "failed");
                        }
                        if (id.equals(activeCommand) && "wake".equals(activeCommandKind))
                            result.getJSONObject("voice_wakeup_request").put("send_completed", error == null);
                        if (id.equals(activeCommand) && ("notify".equals(activeCommandKind) || "voice-cloud".equals(activeCommandKind) || "voice-ble".equals(activeCommandKind) || "voice-native".equals(activeCommandKind) || "wake".equals(activeCommandKind) || error != null))
                            result.getJSONObject("last_command").put("status", error == null ? "completed" : "failed");
                        if (id.equals(activeCommand) && ("voice-cloud".equals(activeCommandKind) || "voice-ble".equals(activeCommandKind) || "voice-native".equals(activeCommandKind))) {
                            JSONObject cloud = result.getJSONObject("glasses_cloud");
                            cloud.put("delivery", new JSONObject().put("command_id", id).put("session_id", result.getString("session_id"))
                                .put("notification_uid", notificationUid).put("status", error == null ? "completed" : "failed"));
                            result.getJSONObject("voice_test").put("phase", error == null ? "finished" : "failed"); writeCloudResult(cloud);
                            if(error==null)voiceTiming(voiceCommand,"answer_end_send_completed",SystemClock.elapsedRealtime());
                            endVoiceRound(voiceCommand,error==null?"answer_sent":"failed");
                            if (error == null && standbyEnabled) rearmStandby();
                        }
                        if (voiceCommand != null && id.equals("voice-stop-" + voiceCommand))
                            result.getJSONObject("voice_test").put("stop_send_completed", error == null);
                        stage("message_send_callback", new JSONObject().put("id", id).put("error", error == null ? JSONObject.NULL : error));
                        if(brightnessTrial!=null&&!brightnessTrial.done){
                            for(int action=1;action<=4;action++)if(id.equals("brightness-"+action+"-"+brightnessTrial.id)){
                                brightnessTrial.callback(action,error==null);publishBrightnessTrial();break;
                            }
                        }
                        Runnable next = sendContinuations.remove(id);
                        if (error == null && next != null) next.run();
                        // A bounded setting probe must retain the connection long enough
                        // to restore after its own transport error. Its journal survives failure.
                        // The convenience sync gets the same exemption for a different reason: the
                        // user never asked for it, so it must not be able to end their session.
                        // Its try/catch only covers the synchronous throw -- the send callback
                        // arrives here, and without this it would reach complete("failed").
                        // Found by independent review 2026-09-22.
                        if (error != null && AUTO_SETTINGS_SYNC.equals(id)) {
                            autoSettingsSyncPending = false;
                            stage("settings_auto_sync_failed", error);
                        }
                        // A setting write is reported completed as soon as it is handed to the
                        // transport, because that is when the command slot frees up. If the send
                        // then fails, the receipt must stop claiming success -- otherwise the user
                        // is told the value was applied while nothing left the phone. The session
                        // itself is spared (see the exemption below); only this command fails.
                        if (error != null && id.startsWith("setting-")) {
                            JSONObject writeReceipt = result.optJSONObject("last_command");
                            if (writeReceipt != null && "apply-setting".equals(writeReceipt.optString("kind")))
                                writeReceipt.put("status", "failed").put("reason", "发送失败：" + error);
                            stage("setting_write_failed", new JSONObject().put("id", id).put("error", error));
                        }
                        if (error != null && !(getPackageName().equals("dev.xr.rayneo.sdklab")
                                && (id.startsWith("brightness-") || AUTO_SETTINGS_SYNC.equals(id)
                                    || id.startsWith("setting-"))))
                            complete("failed", "Business send failed: " + error);
                    } catch (Throwable e) { complete("failed", rootError(e)); }
                });
            }
            return runtime.type("ca.x").getField("a").get(null);
        });
        stage("message_submit_attempt", new JSONObject().put("id", id).put("business", businessName).put("type", type)
                .put("command", json.optString("cmd")).put("bytes", payload.length).put("prefer_low_power", !businessName.equals("VOICE_ASSISTANT")&&!businessName.equals("RECORDING_SERVICE")&&!businessName.equals("AI_SUBTITLE")));
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
            if (business.equals("AI_SUBTITLE") && displayTrial != null) {
                BusinessEnvelope wire = BusinessEnvelope.decode((byte[]) message.get("payload"));
                if (wire.type == 4) {
                    advanceDisplayTrial(displayTrial.stop(SystemClock.elapsedRealtime(), "audio_received_yield_display"));
                } else {
                    JSONObject body = new JSONObject(wire.json);
                    if (displayTrial.owns(body.optString("sid"))) {
                        stage("display_device_reply", new JSONObject().put("type", wire.type).put("code", body.opt("code")));
                        if (wire.type == 8) advanceDisplayTrial(displayTrial.configuration(body.optString("sid"), body.opt("code"), SystemClock.elapsedRealtime()));
                        else if (wire.type == 3) advanceDisplayTrial(displayTrial.stop(SystemClock.elapsedRealtime(), "device_requested_exit"));
                    }
                }
                return;
            }
            if(business.equals("RECORDING_SERVICE")){
                BusinessEnvelope recordingWire=BusinessEnvelope.decodeRecording((byte[])message.get("payload"),recorder!=null&&recorder.captures());
                try{
                    JSONObject recordJson=new JSONObject(recordingWire.json);
                    JSONObject meta=new JSONObject().put("type",recordingWire.type).put("keys",recordJson.names()).put("data_bytes",recordingWire.dataBytes);
                    for(String key:new String[]{"action","code","completed","offset","time","mode","state","type"})if(recordJson.has(key))meta.put("json_"+key,recordJson.opt(key));
                    meta.put("uuid_matches",recorder!=null&&recordJson.optString("uuid").equals(recorder.state().optString("id")));
                    result.put("recording_event",meta);
                    if(recordingWire.type==1&&recordJson.optInt("action")==1){
                        if(connectionReady())receiveDeviceRecordingStart(recordJson);
                        else stage("recording_start_rejected","Pairing not yet confirmed");
                    }
                    else if(recordingPreparing&&pendingDeviceRecordingId!=null&&pendingDeviceRecordingId.equals(recordJson.optString("uuid"))
                        &&recordingWire.type==4&&recordJson.optInt("action")==1)cancelRecordingPreparation(recordJson);
                    else if(recorder!=null)recorder.event(recordingWire);
                    if(recordingWire.type!=3)stage("recording_control_received",meta);
                }finally{if(recordingWire.audio!=null)Arrays.fill(recordingWire.audio,(byte)0);}
                return;
            }
            if(business.equals("SCHEDULE_TODO")){
                // A completion made on the glasses (type 4 -- the only change the glasses can make,
                // 09-23 capture) is taken into the phone list at once, whatever else is running.
                // Review 09-23: a frame that does not decode must not end the session (before this
                // block only a running query/sync decoded, inside its own try).
                BusinessEnvelope todoWire=null;
                try{todoWire=BusinessEnvelope.decode((byte[])message.get("payload"));}catch(Exception e){stage("todo_frame_undecodable",e.getClass().getSimpleName());}
                if(todoWire!=null&&todoWire.type==4){try{glassesCompletedTodo(new JSONObject(todoWire.json));}catch(Exception e){stage("todo_glasses_change_failed",e.getClass().getSimpleName());}return;}
                if(todoWire!=null&&todoFullSync!=null&&!todoFullSync.done){
                    try{todoFullSync.reply(this::reconcileTodos,todoWire.type,new JSONObject(todoWire.json),SystemClock.elapsedRealtime());advanceTodoFullSync();}
                    catch(Exception e){failTodoFullSync(e);}return;
                }
            }
            if(business.equals("SCHEDULE_TODO")&&todoSync!=null&&!todoSync.done){
                try{BusinessEnvelope wire=BusinessEnvelope.decode((byte[])message.get("payload"));
                    if(wire.type==16)todoSync.reply(wire.type,new JSONObject(wire.json),SystemClock.elapsedRealtime());
                    else stage("todo_sync_other_reply",new JSONObject().put("type",wire.type).put("raw",wire.json));
                    advanceTodoSync();
                }catch(Exception e){todoSync.fail(e.getClass().getSimpleName());publishTodoSync();}return;
            }
            if(business.equals("SCHEDULE_TODO")&&todoQuery!=null&&!todoQuery.done){
                try{BusinessEnvelope wire=BusinessEnvelope.decode((byte[])message.get("payload"));todoQuery.reply(wire.type,new JSONObject(wire.json),SystemClock.elapsedRealtime());}
                catch(Exception e){todoQuery.fail("response_decode_failed");}
                publishTodoQuery();return;
            }
            if(business.equals("MARS_FOTA")&&firmwareQuery!=null){
                try{
                    BusinessEnvelope wire=BusinessEnvelope.decode((byte[])message.get("payload"));
                    Object version=null;
                    if(wire.type==1){
                        byte[] rawReply=(byte[])message.get("payload");
                        if(rawReply.length<=4096){StringBuilder hex=new StringBuilder();for(byte value:rawReply)hex.append(String.format(Locale.ROOT,"%02x",value&255));result.put("firmware_query_reply_hex",hex.toString());}
                        JSONObject body=new JSONObject(wire.json);version=body.opt("OsVersion");
                    }
                    firmwareQuery.reply(wire.type,version,SystemClock.elapsedRealtime());publishFirmwareQuery();
                    stage("firmware_query_reply",new JSONObject().put("type",wire.type).put("bytes",((byte[])message.get("payload")).length).put("data_bytes",wire.dataBytes));
                }catch(Exception e){firmwareQuery.reply(1,null,SystemClock.elapsedRealtime());publishFirmwareQuery();stage("firmware_query_decode_failed",e.getClass().getSimpleName());}
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
            if(wire.type==6&&command.equals("sync_time"))clockRequestedByDevice=true;
            if(command.equals("current_weather_update")&&weatherUpdate!=null){
                result.put("weather_business_observation",new JSONObject().put("type",wire.type).put("body",json)
                    .put("received_at_ms",System.currentTimeMillis()).put("association","current session observation; request correlation unverified").put("readback_verified",false));
            }
            JSONObject summary = new JSONObject().put("business", business).put("type", wire.type).put("command", command)
                .put("keys", json.names());
            // 2026-09-22 measurement only, no behaviour change. The wake gap after an assistant
            // exit was not established by earlier observations: the observed session shows the glasses answering
            // the exit within 792ms (mic_status then screen_status) and then 5111ms of nothing
            // before type 1. Which of those two it was -- screen off, mic off -- is invisible
            // because this stage records field NAMES and not values, and screen_raw keeps only
            // the latest. These three commands carry plain numbers, no identities.
            if (command.equals("screen_status") || command.equals("mic_status")
                    || command.equals("hall_state") || command.equals("auto_lock_time")) {
                JSONObject plain = json.optJSONObject("payload");
                if (plain != null && plain.opt("value") instanceof Number)
                    summary.put("value", plain.get("value"));
                summary.put("at_elapsed_ms", SystemClock.elapsedRealtime());
            }
            if (command.equals("glass_preview")) {
                JSONObject plain = json.optJSONObject("payload");
                JSONObject page = optionalData(plain);
                for (String key : new String[]{"scence", "scene", "index"})
                    if (page.opt(key) instanceof Number) summary.put(key, page.get(key));
                summary.put("at_elapsed_ms", SystemClock.elapsedRealtime());
            }
            captureSettingsReply(business,wire.type,json);
            JSONObject general = json.optJSONObject("generalStatus");
            if (statusQuerySent && wire.type == 1 && general != null) {
                result.put("status_reply_received", true);
                summary.put("matched_status_query", true).put("general_status_keys", general.names());
                JSONObject safe = new JSONObject();
                if (getPackageName().equals("dev.xr.rayneo.sdklab")) {
                    for (Iterator<String> keys = general.keys(); keys.hasNext();) {
                        String key = keys.next(); Object value = general.opt(key);
                        if (ReportedStatusPolicy.include(key, value)) safe.put(key, value);
                    }
                    JSONObject shapes = new JSONObject();
                    Object focusRaw = general.opt("focusMode");
                    shapes.put("focusMode", general.has("focusMode") ? statusShape(focusRaw) : "missing");
                    if (focusRaw instanceof JSONObject) {
                        JSONObject focus = (JSONObject) focusRaw, focusSafe = new JSONObject();
                        for (String key : new String[]{"enable", "enableGlassClose"}) {
                            if (!focus.has(key)) continue;
                            Object value = focus.opt(key);
                            shapes.put("focusMode." + key, statusShape(value));
                            if (ReportedStatusPolicy.includeFocus(key, value)) focusSafe.put(key, value);
                        }
                        Object policyRaw = focus.opt("policy");
                        shapes.put("focusMode.policy", focus.has("policy") ? statusShape(policyRaw) : "missing");
                        if (policyRaw instanceof JSONObject) {
                            JSONObject policy = (JSONObject) policyRaw, policySafe = new JSONObject();
                            for (String key : new String[]{"auto", "begin", "end", "weekday", "type"}) {
                                if (!policy.has(key)) continue;
                                Object value = policy.opt(key);
                                shapes.put("focusMode.policy." + key, statusShape(value));
                                if (ReportedStatusPolicy.includeFocus("policy." + key, value)) policySafe.put(key, value);
                            }
                            if (policySafe.length() > 0) focusSafe.put("policy", policySafe);
                        }
                        if (focusSafe.length() > 0) safe.put("focusMode", focusSafe);
                    }
                    result.put("reported_status_shapes", shapes);
                } else {
                    for (String key : new String[]{"battery", "brightness"}) if (general.opt(key) instanceof Number) safe.put(key, general.get(key));
                }
                result.put("reported_status", safe);
                result.put("reported_status_at_ms",System.currentTimeMillis());
                if(brightnessTrial!=null&&!brightnessTrial.done){brightnessTrial.status(safe.opt("brightness"));publishBrightnessTrial();}
                if ("session".equals(businessProbe)) {
                    if (!result.optString("status").equals("sdk_session_ready")) {
                        result.put("status", "sdk_session_ready").put("connect_attempted", true)
                            .put("session_seconds", persistentSession?0:sessionSeconds).put("persistent_session",persistentSession).put("target_address", address);
                        // Review 09-23 (0.5d): "断开眼镜" tapped while this session was still connecting must
                        // not be undone by its later ready transition -- auto_connect only turns on for a
                        // session started after the last explicit disconnect.
                        if(persistentSession&&assistantPrefs().getLong("disconnect_ms",0)<startedWallMs)assistantPrefs().edit().putBoolean("auto_connect",true).apply();
                        if(persistentSession){sessionReadyAtMs=System.currentTimeMillis();AutoReconnect.connected(this);}
                        stage("session_ready", true);
                        autoSyncSettings();
                        if(persistentSession&&!pairingRequested&&(!reconnectMode||repairUnbonded||pairingModeAttempt
                                ||manager.getAdapter().getRemoteDevice(address).getBondState()==BluetoothDevice.BOND_NONE)){
                            activeCommand=UUID.randomUUID().toString();activeCommandKind="pair";
                            result.put("last_command",new JSONObject().put("id",activeCommand).put("kind","pair").put("status","pending").put("started_ms",SystemClock.elapsedRealtime()));
                            requestSystemPairing(activeCommand);
                        }
                        PhoneNotifications.attach(phoneNotificationSink);
                        refreshBondState();
                        display.setText(persistentSession?(connectionReady()?"业务连接与系统配对已就绪，可返回 App 操作。":"通信已接通，正在等待配对确认。\n如出现系统配对提示，请确认。"):
                            "雷鸟调试连接，限时 "+sessionSeconds/60+" 分钟");
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
                JSONObject page = optionalData(payload);
                JSONObject safe = new JSONObject();
                for (String key : new String[]{"scence", "scene", "index"}) if (page.opt(key) instanceof Number) safe.put(key, page.get(key));
                JSONObject app = page.optJSONObject("app");
                if (app != null && app.opt("action") instanceof Number) safe.put("action", app.get("action"));
                result.put("reported_page", safe);
            }
            // A2UI baseline read-back. Weather rides the same type 18, so widgets_v2 is what
            // tells the two apart -- a type 18 frame without that key is not an answer to us.
            // 2026-09-23: 1.0.4.12 answers on type 19; 18 kept for older firmware.
            if (dashboardQuery != null && !dashboardQuery.done && (wire.type == 18 || wire.type == 19) && payload != null) {
                JSONObject body = optionalData(payload);
                org.json.JSONArray widgets = body.optJSONArray("widgets_v2");
                int count = widgets == null ? -1 : widgets.length();
                if (dashboardQuery.reply(business, wire.type, count, SystemClock.elapsedRealtime())) {
                    JSONObject seen = new JSONObject().put("widgets_v2_count", count);
                    // Record only the ids and types; component trees are not decoded here.
                    org.json.JSONArray ids = new org.json.JSONArray();
                    for (int i = 0; widgets != null && i < widgets.length() && i < 20; i++) {
                        JSONObject w = widgets.optJSONObject(i);
                        if (w != null) ids.put(new JSONObject().put("id", w.optString("id"))
                            .put("type", w.optString("type")));
                    }
                    seen.put("widgets", ids);
                    lastBaselineAt = SystemClock.elapsedRealtime();
                    lastBaselineIds.clear();
                    for (int i = 0; i < ids.length(); i++) lastBaselineIds.add(ids.getJSONObject(i).optString("id"));
                    result.put("reported_dashboard", seen);
                    publishDashboardQuery();
                    stage("dashboard_baseline_captured", seen);
                }
            }
            // A2UI card write acknowledgement: type 19 with a numeric code (0 = success). The
            // dashboard reply shares type 19 but carries no code, so it is not taken for an ack.
            if (widgetCommand != null && !widgetCommand.done) {
                // Evidence first: if 1.0.4.12 acknowledges in a different shape than the 1.0.3.15
                // subset, "no_ack" alone would not tell a refusal from a changed reply.
                org.json.JSONArray candidates = result.optJSONArray("widget_candidate_frames");
                if (candidates == null) { candidates = new org.json.JSONArray(); result.put("widget_candidate_frames", candidates); }
                if (candidates.length() < 8) {
                    String frame = json.toString();
                    candidates.put(new JSONObject().put("type", wire.type).put("command", command)
                        .put("keys", json.names()).put("has_code", json.has("code") || (payload != null && payload.has("code")))
                        .put("frame", frame.length() > 300 ? frame.substring(0, 300) : frame)
                        .put("frame_truncated", frame.length() > 300));
                }
            }
            if (widgetCommand != null && !widgetCommand.done && wire.type == 19) {
                // ackRaw / ackMessage: this method already declares raw and message further up.
                Object ackRaw = json.has("code") ? json.opt("code") : payload == null ? null : payload.opt("code");
                Integer ackCode = ackRaw instanceof Number ? ((Number) ackRaw).intValue() : null;
                String ackMessage = json.has("err_msg") ? json.optString("err_msg") : payload == null ? "" : payload.optString("err_msg");
                if (widgetCommand.reply(business, wire.type, ackCode, ackMessage.length() > 200 ? ackMessage.substring(0, 200) : ackMessage,
                        SystemClock.elapsedRealtime())) {
                    String frame = json.toString();
                    JSONObject ack = new JSONObject().put("code", ackCode).put("err_msg", ackMessage)
                        .put("frame", frame.length() > 300 ? frame.substring(0, 300) : frame)
                        .put("frame_truncated", frame.length() > 300);
                    result.put("reported_widget_ack", ack);
                    publishWidgetCommand();
                    stage("widget_ack_captured", ack);
                }
            }
            // the glasses change brightness on their own and push the new level. Until
            // now only the field names were logged, so a level that moved by itself was
            // indistinguishable from a write of ours that had failed to stick -- which is exactly
            // how 7→5 was misread on 2026-09-16. The official capture
            // recorded the push as
            // {"value":5,"mode":1,"data":{"lux":11.21}}: mode 1 carries an ambient reading.
            // A push without a level is not a brightness reading. Storing one anyway would stamp
            // at_ms with "something just happened" while dropping the level we already knew --
            // flagged by independent review 2026-09-22 as the one place here that turns an
            // unknown into a stated fact.
            if (command.equals("brightness_change") && payload != null
                    && payload.opt("value") instanceof Number) {
                JSONObject light = new JSONObject().put("at_ms", System.currentTimeMillis())
                    .put("brightness", payload.get("value"));
                if (payload.opt("mode") instanceof Number) light.put("mode", payload.get("mode"));
                JSONObject extra = optionalData(payload);
                // lux is a float in the official capture; keep it as reported, decode nothing else.
                if (extra.opt("lux") instanceof Number) light.put("lux", extra.get("lux"));
                result.put("glasses_brightness", light);
                summary.put("brightness", light.opt("brightness"));
                if (light.has("mode")) summary.put("mode", light.get("mode"));
                if (light.has("lux")) summary.put("lux", light.get("lux"));
            }
            // G12: the push carries more than the percentage, and until now none of it was read.
            // Measured 2026-09-19 (capture B6): {"value":70,"mode":1,"data":"{"chargeType":-1,
            // "batt_temp":33}"}. mode 1 means the data string is present. The percentage alone
            // never told the user whether the glasses were charging, which is what they asked for.
            if (command.equals("battery_change") && payload != null) {
                JSONObject power = new JSONObject().put("at_elapsed_ms", SystemClock.elapsedRealtime());
                if (payload.opt("value") instanceof Number) power.put("battery", payload.get("value"));
                if (payload.opt("mode") instanceof Number) power.put("mode", payload.get("mode"));
                JSONObject extra = optionalData(payload);
                // chargeType is an enum whose values are not decoded; keep the number, name nothing.
                for (String key : new String[]{"chargeType", "batt_temp"})
                    if (extra.opt(key) instanceof Number) power.put(key, extra.get(key));
                result.put("glasses_power", power);
                summary.put("battery", power.opt("battery"));
                if (power.has("chargeType")) summary.put("chargeType", power.get("chargeType"));
            }
            // G13: measured 2026-09-19 (capture B5). boxVersion is a firmware version string, not
            // an identity. boxColor is an undecoded enum -- the number is kept, no label invented.
            if (command.equals("glasses_box_state") && payload != null) {
                JSONObject box = optionalData(payload);
                JSONObject safe = new JSONObject().put("at_elapsed_ms", SystemClock.elapsedRealtime());
                for (String key : new String[]{"inBox", "isCharging", "boxOpen"})
                    if (box.opt(key) instanceof Boolean) safe.put(key, box.get(key));
                for (String key : new String[]{"boxBatteryLevel", "boxColor"})
                    if (box.opt(key) instanceof Number) safe.put(key, box.get(key));
                String version = box.optString("boxVersion", "");
                if (version.matches("[0-9][0-9.]{0,15}")) safe.put("boxVersion", version);
                result.put("glasses_box", safe);
                summary.put("box", safe);
            }
            stage("message_received", summary);
        } catch (Throwable e) { complete("failed", rootError(e)); }
    }

    /** The optional {@code data} field is a JSON string. {@code optString(...,"{}")} only covers
     * the key being absent; present-and-empty is just as real a shape -- the observed write packet uses an
     * empty string for "no extra data" -- and {@code new JSONObject("")} throws. That exception is
     * caught by the session tick's {@code catch(Throwable)}, which fails the WHOLE session rather
     * than skipping one push. One malformed optional field must never cost the session.
     * Flagged by independent review 2026-09-22. */
    private static JSONObject optionalData(JSONObject payload) {
        String raw = payload == null ? "" : payload.optString("data", "");
        if (raw.trim().isEmpty()) return new JSONObject();
        try { return new JSONObject(raw); }
        catch (org.json.JSONException malformed) { return new JSONObject(); }
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

    /** Called once by ConnectionService on the main thread. False when another session already owns
     * the link; this object is then discarded without touching the SDK. */
    boolean start(Intent intent) {
        startIntent = intent;
        // Claim before publishing status, scheduling the watchdog or touching the SDK.
        if (!sessions.claim(this)) {
            finished = true;
            voiceWorker.shutdownNow();
            return false;
        }
        connectionActive=true;
        startedAt = SystemClock.elapsedRealtime();
        startedWallMs = System.currentTimeMillis();
        display.setText("雷鸟 SDK 测试\n正在检查指定眼镜与本机权限…");
        // Nothing to draw or hide any more: a background reconnect has no window to take the screen.
        if(getIntent().getBooleanExtra("auto_reconnect",false))AutoReconnect.sessionStarted(this);
        begin();
        return true;
    }

    /** The former Activity onCreate body after the claim, unchanged. */
    private void begin() {
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
            pairingModeAttempt=getIntent().getBooleanExtra("pairing_mode_attempt",false);
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
                .put("target_address",address).put("pairing_mode_attempt",pairingModeAttempt)
                .put("sdk_int", Build.VERSION.SDK_INT).put("package", getPackageName());
            stage("begin", true);
            if (Build.VERSION.SDK_INT < 31 || !BluetoothAdapter.checkBluetoothAddress(address))
                throw new IllegalArgumentException("Android 12+ and an explicit Bluetooth address required");
            for (String permission : new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN})
                if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) throw new SecurityException("Missing " + permission);
            manager = getSystemService(BluetoothManager.class);
            if (manager == null || manager.getAdapter() == null || !manager.getAdapter().isEnabled())
                throw new IllegalStateException("Bluetooth unavailable");
            BluetoothDevice systemDevice = manager.getAdapter().getRemoteDevice(address);
            boolean systemConnected = systemConnected();
            int bondState = systemDevice.getBondState();
            stage("system_preflight", new JSONObject().put("gatt_connected", systemConnected).put("bond_state", bondState));
            boolean hasSaved = !getSharedPreferences("rayneo_net_bonded_devices", MODE_PRIVATE).getAll().isEmpty();
            labExistingSystemBond = ConnectionAttemptPolicy.labExistingBond(getPackageName(),
                pairingModeAttempt && getIntent().getBooleanExtra("pairing_ready", false), hasSaved, systemConnected, bondState);
            result.put("lab_existing_system_bond_attempt", labExistingSystemBond);
            if (labExistingSystemBond) stage("lab_existing_system_bond_admitted",
                "Explicit lab-only guest attempt; preserve Android bond and other app data; actual SDK bond evidence still required");
            repairUnbonded=getIntent().getBooleanExtra("repair_unbonded",false)&&pairingModeAttempt
                &&hasSaved&&bondState==BluetoothDevice.BOND_NONE;
            result.put("repair_unbonded",repairUnbonded);
            if (connectMode && (systemConnected || (!hasSaved && !labExistingSystemBond && (!getIntent().getBooleanExtra("pairing_ready", false) || bondState != BluetoothDevice.BOND_NONE))
                    || (hasSaved && bondState != BluetoothDevice.BOND_BONDED && !repairUnbonded))) {
                result.put("connection_issue",systemConnected?"existing_gatt":hasSaved?"saved_bond_mismatch":"first_pairing_not_ready");
                complete("blocked_or_failed", systemConnected?"系统仍报告眼镜已有连接，请断开原连接后重试。":hasSaved?"已保存眼镜与系统配对状态不一致，需要核对配对。":"首次连接条件未满足，需要核对现有绑定与配对状态。"); return;
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
                    public boolean wantsAudio() { LabAsrTrial current=asrTrial; return !finished && ((cloudVoice && voiceRecording) || (current!=null&&current.capturing&&!current.done)); }
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
            if ("session".equals(businessProbe)) {
                ConnectionService.start(this,this);
                connectionServiceStarted = true;
            }
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

    /** The hosting service is going away with this session still open (e.g. stopped by the system). */
    void hostDestroyed() {
        if (!finished) complete("failed", HOST_DESTROYED);
    }
    static final String HOST_DESTROYED = "Session host destroyed";
}
