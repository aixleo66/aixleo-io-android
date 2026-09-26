from pathlib import Path
import tempfile
import unittest
import lab


class VoiceRecordingChecks(unittest.TestCase):
    def test_recording_identity_start_confirmation_and_exit_owner(self):
        java = r'''package dev.xr.rayneo.probe;
import org.json.*;
public final class VoiceRecordingCheck {
 static void check(boolean value){if(!value)throw new AssertionError();}
 public static void main(String[] args)throws Exception{
  check(VoiceRecordingAction.matches("好的，帮我开始录音。"));
  check(!VoiceRecordingAction.matches("不要开始录音。"));
  check(!VoiceRecordingAction.matches("怎么开始录音？"));
  check(!VoiceRecordingAction.matches("他说开始录音。"));
  check(VoiceRecordingAction.ownsVoiceExit("voice","voice","voice-native",true));
  check(!VoiceRecordingAction.ownsVoiceExit("voice","record","record-start",true));
  check(!VoiceRecordingAction.ownsVoiceExit(null,"record","record-start",true));
  check(!VoiceRecordingAction.ownsVoiceExit("voice","voice","voice-native",false));
  check(!VoiceRecordingAction.ownsVoiceExit("old","new","voice-native",true));
  VoiceRecordingAction action=new VoiceRecordingAction("session","voice","record","开始录音");
  check(!action.observe("other",new JSONObject().put("phase","recording").put("id","wrong")));
  check(action.observe("record",new JSONObject().put("phase","connecting"))&&!action.started);
  check(action.observe("record",new JSONObject().put("phase","starting").put("id","audio"))&&!action.started);
  check(action.observe("record",new JSONObject().put("phase","recording").put("id","audio"))&&action.started);
  check(!action.observe("stop",new JSONObject().put("phase","saved").put("id","other")));
  check(action.observe("stop",new JSONObject().put("phase","stopping").put("id","audio")));
  check(action.observe("stop",new JSONObject().put("phase","saving").put("id","audio"))&&!action.snapshot().optBoolean("saved"));
  check(action.observe("stop",new JSONObject().put("phase","saved").put("id","audio"))&&action.snapshot().optBoolean("saved"));
  check(!action.observe("later",new JSONObject().put("phase","recording").put("id","later")));
  action=new VoiceRecordingAction("session","v2","r2","开始录音");
  check(action.observe("r2",new JSONObject().put("phase","failed"))&&!action.started);
  check(!action.snapshot().optBoolean("saved"));
  System.out.println("voice recording identity checks passed");
 }
}'''
        s=lab.settings()
        with tempfile.TemporaryDirectory() as tmp:
            source=Path(tmp)/'VoiceRecordingCheck.java'
            source.write_text(java,encoding='utf-8')
            lab.command([lab.tool(s,'javac'),'-encoding','UTF-8','-d',tmp,source,
                         lab.ROOT/'app/src/VoiceRecordingAction.java',lab.ROOT/'app/src/VoicePhrase.java',*list((lab.ROOT/'tests/stubs/org/json').glob('*.java'))])
            r=lab.command([lab.tool(s,'java'),'-cp',tmp,'dev.xr.rayneo.probe.VoiceRecordingCheck'])
            self.assertIn(b'voice recording identity checks passed',r.stdout)
