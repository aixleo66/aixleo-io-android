package dev.xr.rayneo.probe;

import org.json.*;

/** Correlates a completed speech round with the existing app-started recorder. */
final class VoiceRecordingAction {
    final String session,voiceCommand,recordCommand,transcript;
    String recordingId="",phase="preparing";
    boolean started;
    VoiceRecordingAction(String session,String voice,String command,String text){
        this.session=session;voiceCommand=voice;recordCommand=command;transcript=text;
    }
    /** Explicit closed set, matched after the shared normalization. The set held two entries and
     * the user said "打开录音", which fell through to the model and came back as "I cannot
     * do that" (observed 2026-09-20). Widened to the phrasings people actually use,
     * still closed and still refusing questions: nothing is inferred from free speech. */
    static boolean matches(String text){
        String value=VoicePhrase.trimTrailingInterjection(VoicePhrase.normalize(text));
        if(value.isEmpty()||VoicePhrase.isQuestion(value))return false;
        return value.equals("开始录音")||value.equals("启动录音")||value.equals("打开录音")
            ||value.equals("录音")||value.equals("开始录制")||value.equals("启动录制")
            ||value.equals("打开录制")||value.equals("开始录")||value.equals("录一下")
            ||value.equals("开始录一下")||value.equals("录个音")||value.equals("录音开始");
    }
    static boolean ownsVoiceExit(String voice,String active,String kind,boolean pending){
        return pending&&voice!=null&&voice.equals(active)
            &&("voice-native".equals(kind)||"voice-cloud".equals(kind)||"voice-ble".equals(kind));
    }
    boolean observe(String active,JSONObject state){
        if(phase.equals("saved")||phase.equals("failed")||phase.equals("cancelled"))return false;
        String id=state.optString("id"),next=state.optString("phase");
        if(recordingId.isEmpty()){
            if(!recordCommand.equals(active))return false;
            if(!id.isEmpty())recordingId=id;
        }else if(!recordingId.equals(id))return false;
        if(!(next.equals("connecting")||next.equals("starting")||next.equals("recording")||next.equals("stopping")
            ||next.equals("saving")||next.equals("saved")||next.equals("failed")||next.equals("cancelled")))return false;
        phase=next;if(next.equals("recording"))started=true;return true;
    }
    JSONObject snapshot()throws Exception{
        return new JSONObject().put("session_id",session).put("voice_command_id",voiceCommand)
            .put("record_command_id",recordCommand).put("recording_id",recordingId).put("transcript",transcript)
            .put("phase",phase).put("start_confirmed",started).put("saved",phase.equals("saved"))
            .put("source","voice").put("recording_audio_uploaded",false).put("stop_entry","existing_phone_or_glasses_control");
    }
}
