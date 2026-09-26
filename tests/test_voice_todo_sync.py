from pathlib import Path
import tempfile
import unittest
import lab
from test_interactions import method


class VoiceTodoSyncChecks(unittest.TestCase):
    def test_production_publish_pending_delivery_once_and_cancel_hook(self):
        sdk = (lab.ROOT / 'app/src/SdkProbeActivity.java').read_text(encoding='utf-8')
        methods = '\n'.join(method(sdk, name) for name in (
            'private boolean voiceOwnsTodoSync(', 'private void advanceTodoSync(',
            'private void publishTodoSync(', 'private void cancelVoiceTodoSync(',
            'private void endVoiceRound(', 'private boolean currentRound(',
            'private void writeVoiceTodoReceipt(', 'private void deliverVoiceTodo('))
        java = r'''package dev.xr.rayneo.probe;
import org.json.*;
import java.io.*;
import android.os.SystemClock;
// Android/POSIX rename replaces the destination; Windows java.io.File does not.
// Supply only this platform edge while keeping production receipt methods verbatim.
class File extends java.io.File {
 File(String p){super(p);}File(File p,String c){super(p,c);}
 public boolean renameTo(java.io.File target){try{java.nio.file.Files.move(toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);return true;}catch(IOException e){return false;}}
}
public final class VoiceTodoWiringCheck {
 JSONObject result=new JSONObject();LabTodoSync todoSync;VoiceTodoSyncRound voiceTodoSync;
 String activeCommand="voice",voiceCommand="voice";boolean finished,finishing;int answers,writes,reads;
 File root;Rounds voiceRounds=new Rounds();
 static class Rounds {void finish(String c,String o,long t){}}
 File getFilesDir(){return root;}
 boolean labNativeMode(){return true;}
 void publishVoiceTiming(){}
 void writeCloudResult(JSONObject p){}
 void sendNativeAnswer(JSONObject p,String c){check(result.getJSONObject("last_command").getString("status").equals("pending"));answers++;}
 void sendBusiness(String b,int type,JSONObject p,String id){if(type==2)writes++;else if(type==15)reads++;}
 static void check(boolean v){if(!v)throw new AssertionError();}
 void setup(String id,File directory)throws Exception{
  root=directory;root.mkdirs();activeCommand=id;voiceCommand=id;
  result=new JSONObject().put("session_id","session").put("target_address","test-address")
    .put("last_command",new JSONObject().put("status","pending")).put("voice_test",new JSONObject());
  JSONObject p=new JSONObject().put("job_id",id).put("session_id","session").put("answer",new JSONObject()
    .put("action",new JSONObject().put("kind","create").put("status","succeeded").put("task_id","local")));
  todoSync=new LabTodoSync(id,"push_local",285830507325763586L,"task",1700000000123L,0,1800000000L,0);
  voiceTodoSync=new VoiceTodoSyncRound(id,"session",p,todoSync);
 }
 // PRODUCTION_METHODS
 public static void main(String[] args)throws Exception{
  VoiceTodoWiringCheck h=new VoiceTodoWiringCheck();File root=new File(args[0]);h.setup("verified",root);
  h.todoSync.done=true;h.todoSync.phase="verified";h.todoSync.effect="target_fields_verified";
  h.publishTodoSync();h.publishTodoSync();check(h.answers==1&&h.result.getJSONObject("last_command").getString("status").equals("pending"));
  check(new File(root,"voice-actions/verified.json").isFile()&&new File(root,"todo-sync/verified.json").isFile());
  h.endVoiceRound("verified","answer_sent");check(!h.voiceTodoSync.cancelled);
  h.setup("cancel-before",root);h.todoSync.phase="writing";h.todoSync.payload=new JSONObject();
  h.endVoiceRound("cancel-before","cancelled_by_device");h.advanceTodoSync();check(h.todoSync.done&&h.writes==0&&h.answers==1);
  h.setup("cancel-after",root);h.todoSync.phase="writing";h.todoSync.writeSent=true;
  h.endVoiceRound("cancel-after","cancelled_by_host");h.todoSync.sent(h.todoSync.writeId(),true,1);h.advanceTodoSync();
  check(h.todoSync.phase.equals("write_unconfirmed")&&!h.todoSync.writeAck&&h.writes==0&&h.answers==1);
  h.setup("owner-changed",root);h.activeCommand="new";h.advanceTodoSync();check(h.todoSync.done&&h.answers==1&&h.reads==0);
  h.setup("host",root);h.voiceTodoSync=null;h.todoSync.done=true;h.todoSync.phase="verified";h.publishTodoSync();
  check(h.result.getJSONObject("last_command").getString("status").equals("completed")&&h.answers==1);
  System.out.println("production voice todo wiring checked");
 }
}'''.replace('// PRODUCTION_METHODS', methods)
        settings = lab.settings()
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / 'VoiceTodoWiringCheck.java'
            source.write_text(java, encoding='utf-8')
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8', '-d', tmp, source,
                         *[lab.ROOT / ('app/src/' + n + '.java') for n in ('VoiceTodoSyncRound', 'LabTodoSync', 'LabTodoQuery')],
                         lab.ROOT / 'tests/stubs/android/os/SystemClock.java', lab.ROOT / 'tests/stubs/android/util/Log.java',
                         *list((lab.ROOT / 'tests/stubs/org/json').glob('*.java'))])
            result = lab.command([lab.tool(settings, 'java'), '-cp', tmp, 'dev.xr.rayneo.probe.VoiceTodoWiringCheck', str(Path(tmp) / 'receipts')])
            self.assertIn(b'production voice todo wiring checked', result.stdout)

    def test_action_sync_result_ownership_and_cancellation(self):
        java = r'''package dev.xr.rayneo.probe;
import org.json.*;
public final class VoiceTodoSyncCheck {
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 static JSONObject action(String kind){return new JSONObject().put("answer",new JSONObject().put("text","local saved")
   .put("action",new JSONObject().put("kind",kind).put("status","succeeded").put("task_id","local-1").put("glasses_synced",false)));}
 static JSONObject item(long id){return new JSONObject().put("eventType",1).put("eventID",id).put("title","milk").put("isImportant",false)
   .put("status",0).put("createTime",1700000000123L).put("lastModifiedTime",1700000000L);}
 static JSONObject page(JSONObject... rows){JSONArray a=new JSONArray();for(JSONObject r:rows)a.put(r);return new JSONObject().put("todoTotal",rows.length).put("isLastBatch",true).put("dataList",a);}
 static LabTodoSync sync(String id,String mode,long status){return new LabTodoSync(id,mode,285830507325763586L,"milk",1700000000123L,status,1800000000L,0);}
 static void before(LabTodoSync s,JSONObject... rows)throws Exception{s.sent(s.readId(),true,1);s.reply(16,page(rows),2);}
 static void after(LabTodoSync s,JSONObject... rows)throws Exception{s.writeSent=true;s.sent(s.writeId(),true,3);s.advance(503);s.sent(s.readId(),true,504);s.reply(16,page(rows),505);}
 public static void main(String[] args)throws Exception{
  JSONObject p=action("create");check(VoiceTodoSyncRound.eligible(p.getJSONObject("answer")));
  LabTodoSync s=sync("voice-create","push_local",0);VoiceTodoSyncRound r=new VoiceTodoSyncRound(s.id,"session",p,s);
  check(!r.finish("session",s.id,true));before(s,item(2));
  check(s.phase.equals("writing")&&!r.finish("session",s.id,true));after(s,item(2),s.payload);
  check(s.passed()&&r.finish("session",s.id,true));check(!r.finish("session",s.id,true));
  check(p.getJSONObject("answer").getJSONObject("action").optBoolean("glasses_synced"));
  check(p.getString("text").contains("同步到眼镜"));
  p=action("complete");s=sync("voice-complete","update_local",1);r=new VoiceTodoSyncRound(s.id,"session",p,s);
  before(s,item(s.eventId),item(2));after(s,item(2));check(s.passed()&&r.finish("session",s.id,true));
  check(p.getString("text").contains("移出"));
  p=action("create");s=sync("cancel-before","push_local",0);r=new VoiceTodoSyncRound(s.id,"session",p,s);
  before(s,item(2));r.cancel("device_exit");s.advance(20);check(s.done&&!s.writeSent&&!r.finish("session",s.id,true));
  check(p.getJSONObject("answer").getJSONObject("action").getString("status").equals("succeeded"));
  check(p.getJSONObject("delivery").getString("status").equals("cancelled"));
  p=action("create");s=sync("cancel-after","push_local",0);r=new VoiceTodoSyncRound(s.id,"session",p,s);
  before(s,item(2));s.writeSent=true;r.cancel("device_exit");s.sent(s.writeId(),true,3);s.reply(16,page(item(2),s.payload),4);s.advance(600);
  check(s.done&&!s.passed()&&!s.writeAck&&s.phase.equals("write_unconfirmed")&&!r.finish("session",s.id,true));
  p=action("create");s=sync("timeout","push_local",0);r=new VoiceTodoSyncRound(s.id,"session",p,s);s.advance(45000);
  check(r.finish("session",s.id,true)&&p.getString("text").contains("手机待办已保存")&&!p.getJSONObject("answer").getJSONObject("action").optBoolean("glasses_synced"));
  p=action("complete");VoiceTodoSyncRound.annotate(p,false,"not_mapped","","","no_glasses_identity");
  check(p.getString("text").contains("尚未同步")&&p.getJSONObject("answer").getJSONObject("action").getString("task_id").equals("local-1"));
  check(!VoiceTodoSyncRound.eligible(action("list").getJSONObject("answer")));
  p=action("create");p.getJSONObject("answer").getJSONObject("action").put("status","rejected");check(!VoiceTodoSyncRound.eligible(p.getJSONObject("answer")));
  p=action("create");s=sync("wrong-session","push_local",0);r=new VoiceTodoSyncRound(s.id,"session",p,s);s.fail("owner_changed");check(!r.finish("new-session",s.id,true));
  check(!r.current("session","new-command",true)&&!r.current("session",s.id,false));
  System.out.println("voice todo sync outcomes and ownership checked");
 }
}'''
        settings = lab.settings()
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / 'VoiceTodoSyncCheck.java'
            source.write_text(java, encoding='utf-8')
            lab.command([lab.tool(settings, 'javac'), '-encoding', 'UTF-8', '-d', tmp, source,
                         *[lab.ROOT / ('app/src/' + n + '.java') for n in ('VoiceTodoSyncRound', 'LabTodoSync', 'LabTodoQuery')],
                         *list((lab.ROOT / 'tests/stubs/org/json').glob('*.java'))])
            result = lab.command([lab.tool(settings, 'java'), '-cp', tmp, 'dev.xr.rayneo.probe.VoiceTodoSyncCheck'])
            self.assertIn(b'voice todo sync outcomes and ownership checked', result.stdout)
