from pathlib import Path
import tempfile
import unittest
import lab
from test_interactions import method


def src(name):
    return (lab.ROOT / name).read_text(encoding='utf-8')


class TodoFullSyncChecks(unittest.TestCase):
    """09-23 plan stage 1: the official model -- read, reconcile, push the whole list (type 6),
    delete (type 7), read back. Type 14 acknowledges deletion; types 15/16 query and return the list."""

    def test_full_table_round(self):
        java = r'''package dev.xr.rayneo.probe;
import org.json.*;
import java.util.*;
public final class TodoFullSyncCheck {
 static void check(boolean ok,String what){if(!ok)throw new AssertionError(what);}
 static JSONObject g(long id,String title,int status)throws Exception{return new JSONObject().put("eventType",1).put("eventID",id).put("title",title).put("isImportant",false).put("status",status).put("createTime",1790156474505L).put("lastModifiedTime",1790156476L);}
 static JSONObject page(JSONObject...rows)throws Exception{JSONArray a=new JSONArray();for(JSONObject r:rows)a.put(r);return new JSONObject().put("todoTotal",rows.length).put("batchNo",1).put("isLastBatch",true).put("dataList",a);}
 public static void main(String[]a)throws Exception{
  long id=288839564836401153L;
  final List<JSONObject> phone=new ArrayList<>();phone.add(TodoFullSync.item(id,"手机标题",true,1790156474505L,1790156500L));phone.add(TodoFullSync.item(id+7,"新增",false,1790156600000L,1790156600L));
  final LinkedHashMap<Long,JSONObject> seen=new LinkedHashMap<>();
  TodoFullSync.Host host=glasses->{seen.putAll(glasses);return new TodoFullSync.Plan(phone,Arrays.asList(id+9),1,0);};
  TodoFullSync s=new TodoFullSync("full",0);
  s.readSent=true;s.sent(host,s.readId(),true,1);s.reply(host,16,page(g(id,"眼镜标题",0),g(id+9,"已删",0)),2);
  check(seen.size()==2&&s.phase.equals("writing_table"),"reconciled then writing");
  check(s.table.getInt("total")==2&&s.table.optBoolean("isLastBatch")&&s.table.getJSONArray("eventList").length()==2,"table shape (type 6)");
  check(s.deleteCommand.getInt("todoTotal")==1&&s.deleteCommand.getJSONArray("eventIDList").toString().equals("["+(id+9)+"]")&&s.deleteCommand.getInt("eventType")==1,"delete shape (type 7)");
  s.tableSent=true;s.sent(host,s.tableId(),true,10);check(s.phase.equals("writing_delete"),"delete after table");
  s.deleteSent=true;s.sent(host,s.deleteId(),true,11);check(s.phase.equals("waiting_delete_receipt"),"await receipt");
  s.reply(host,14,new JSONObject().put("sourceType",7).put("successCount",0).put("failList",new JSONArray().put(new JSONObject().put("eventID",id+9).put("message","todo not found"))),12);
  check(s.phase.equals("waiting_readback")&&s.deleteReceipt!=null,"receipt recorded, not required");
  s.advance(host,600);check(s.phase.equals("reading_after")&&!s.readSent,"read after");
  s.readSent=true;s.sent(host,s.readId(),true,601);
  JSONObject echoed=new JSONObject(phone.get(0).toString()).put("lastModifiedTime",1790156620244L);
  s.reply(host,16,page(echoed,phone.get(1)),602);
  check(s.passed()&&s.after.length()==2,"verified; glasses clock on lastModifiedTime ignored");
  final Set<Long> doneHere=new HashSet<>(Arrays.asList(id+20));
  TodoFullSync.Host plain=glasses->new TodoFullSync.Plan(phone,new ArrayList<>(),0,0,doneHere);
  s=new TodoFullSync("kept",0);s.readSent=true;s.sent(plain,s.readId(),true,1);s.reply(plain,16,page(g(id+20,"眼镜上完成",1)),2);
  s.tableSent=true;s.sent(plain,s.tableId(),true,3);s.advance(plain,900);s.readSent=true;s.sent(plain,s.readId(),true,901);
  s.reply(plain,16,page(phone.get(0),phone.get(1),g(id+20,"眼镜上完成",1)),902);
  check(s.passed()&&s.keptCompleted==1,"glasses keep their own completed item (09-23 20:03 device)");
  check(s.after.length()==3&&s.receipt().getInt("kept_completed_on_glasses")==1,"kept item recorded in receipt and after");
  s=new TodoFullSync("extra",0);s.readSent=true;s.sent(plain,s.readId(),true,1);s.reply(plain,16,page(),2);
  s.tableSent=true;s.sent(plain,s.tableId(),true,3);s.advance(plain,900);s.readSent=true;s.sent(plain,s.readId(),true,901);
  s.reply(plain,16,page(phone.get(0),phone.get(1),g(id+21,"多出的未完成",0)),902);
  check(!s.passed()&&s.issue.equals("readback_extra_item"),"an extra pending item still fails");
  check(s.keptCompleted==0,"no partial count on failure");
  // completed on the glasses but not completed on the phone: not allowed
  s=new TodoFullSync("unknown",0);TodoFullSync.Host none2=glasses->new TodoFullSync.Plan(phone,new ArrayList<>(),0,0);
  s.readSent=true;s.sent(none2,s.readId(),true,1);s.reply(none2,16,page(),2);s.tableSent=true;s.sent(none2,s.tableId(),true,3);s.advance(none2,900);s.readSent=true;s.sent(none2,s.readId(),true,901);
  s.reply(none2,16,page(phone.get(0),phone.get(1),g(id+22,"来历不明",1)),902);
  check(!s.passed()&&s.issue.equals("readback_extra_item"),"unknown completed item fails");
  // deleted on the phone but still on the glasses (even as completed): not allowed
  final Set<Long> alsoDone=new HashSet<>(Arrays.asList(id+23));
  TodoFullSync.Host del=glasses->new TodoFullSync.Plan(phone,Arrays.asList(id+23),0,0,alsoDone);
  s=new TodoFullSync("deleted",0);s.readSent=true;s.sent(del,s.readId(),true,1);s.reply(del,16,page(g(id+23,"删掉的",1)),2);
  s.tableSent=true;s.sent(del,s.tableId(),true,3);s.deleteSent=true;s.sent(del,s.deleteId(),true,4);s.advance(del,3005);s.advance(del,3506);
  s.readSent=true;s.sent(del,s.readId(),true,3507);s.reply(del,16,page(phone.get(0),phone.get(1),g(id+23,"删掉的",1)),3508);
  check(!s.passed()&&s.issue.equals("readback_deleted_still_present"),"deleted item left on glasses fails");
  // missing and extra together: missing is reported whatever the row order
  s=new TodoFullSync("order",0);s.readSent=true;s.sent(plain,s.readId(),true,1);s.reply(plain,16,page(),2);s.tableSent=true;s.sent(plain,s.tableId(),true,3);s.advance(plain,900);s.readSent=true;s.sent(plain,s.readId(),true,901);
  s.reply(plain,16,page(g(id+21,"多出的未完成",0),phone.get(0)),902);
  check(!s.passed()&&s.issue.equals("readback_count_mismatch"),"missing reported before extras");
  s=new TodoFullSync("short",0);s.readSent=true;s.sent(host,s.readId(),true,1);s.reply(host,16,page(g(id,"x",0)),2);
  s.tableSent=true;s.sent(host,s.tableId(),true,3);s.deleteSent=true;s.sent(host,s.deleteId(),true,4);s.advance(host,3005);s.advance(host,3506);
  s.readSent=true;s.sent(host,s.readId(),true,3507);s.reply(host,16,page(phone.get(0)),3508);
  check(s.done&&!s.passed()&&s.issue.equals("readback_count_mismatch")&&s.phase.equals("write_unconfirmed"),"count mismatch after write");
  TodoFullSync.Host none=glasses->new TodoFullSync.Plan(phone,new ArrayList<>(),0,0);
  s=new TodoFullSync("title",0);s.readSent=true;s.sent(none,s.readId(),true,1);s.reply(none,16,page(),2);
  check(s.deleteCommand==null,"no delete without deletions");
  s.tableSent=true;s.sent(none,s.tableId(),true,3);check(s.phase.equals("waiting_readback"),"straight to read back");
  s.advance(none,900);s.readSent=true;s.sent(none,s.readId(),true,901);
  s.reply(none,16,page(new JSONObject(phone.get(0).toString()).put("title","别的"),phone.get(1)),902);
  check(!s.passed()&&s.issue.equals("readback_item_mismatch"),"content mismatch");
  s=new TodoFullSync("sendfail",0);s.readSent=true;s.sent(none,s.readId(),true,1);s.reply(none,16,page(),2);s.tableSent=true;s.sent(none,s.tableId(),false,3);
  check(s.done&&s.phase.equals("write_unconfirmed")&&s.issue.equals("table_send_failed"),"send failure");
  boolean rejected=false;try{TodoFullSync.tableOf(Arrays.asList(phone.get(0),phone.get(0)));}catch(IllegalArgumentException e){rejected=true;}check(rejected,"duplicate ids rejected");
  rejected=false;try{TodoFullSync.tableOf(Arrays.asList(g(id,"done",1)));}catch(IllegalArgumentException e){rejected=true;}check(rejected,"completed never in pending table");
  List<JSONObject> many=new ArrayList<>();for(int i=0;i<TodoFullSync.MAX_ITEMS+1;i++)many.add(TodoFullSync.item(id+i,"x"+i,false,1790156474505L,1L));
  TodoFullSync.Host big=glasses->new TodoFullSync.Plan(many,new ArrayList<>(),0,0);
  s=new TodoFullSync("big",0);s.readSent=true;s.sent(big,s.readId(),true,1);s.reply(big,16,page(),2);check(s.done&&!s.tableSent&&s.issue.equals("too_many_items"),"limit before any write");
  s=new TodoFullSync("timeout",0);s.advance(none,60000);check(s.done&&!s.tableSent&&s.issue.equals("sync_timeout"),"timeout");
  System.out.println("full table sync checks passed");
 }
}'''
        s = lab.settings()
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / 'TodoFullSyncCheck.java'
            source.write_text(java, encoding='utf-8')
            lab.command([lab.tool(s, 'javac'), '-encoding', 'UTF-8', '-d', tmp, source, lab.ROOT / 'app/src/TodoFullSync.java',
                         lab.ROOT / 'app/src/LabTodoSync.java', lab.ROOT / 'app/src/LabTodoQuery.java',
                         *list((lab.ROOT / 'tests/stubs/org/json').glob('*.java'))])
            result = lab.command([lab.tool(s, 'java'), '-cp', tmp, 'dev.xr.rayneo.probe.TodoFullSyncCheck'])
            self.assertIn(b'full table sync checks passed', result.stdout)

    def test_session_sends_only_owned_frames_of_the_official_types(self):
        activity = src('app/src/SdkProbeActivity.java')
        guard = activity[activity.index('TodoFullSync full=todoFullSync;'):activity.index('Unowned todo command')]
        for t, owner in (('15', 'full.readId()'), ('6', 'full.tableId()'), ('7', 'full.deleteId()')):
            self.assertIn('type==%s&&id.equals(%s)' % (t, owner), guard)
        self.assertIn('json.toString().equals(full.table.toString())', guard)
        # The guard really rejects: nothing but an owned query/sync/full-sync frame passes.
        self.assertIn('(!readOnly&&!syncAllowed&&!fullAllowed))throw new IllegalArgumentException("Unowned todo command")', activity)
        advance = method(activity, 'private void advanceTodoFullSync()')
        self.assertIn('sendBusiness("SCHEDULE_TODO",6,sync.table,sync.tableId());', advance)
        self.assertIn('sendBusiness("SCHEDULE_TODO",7,sync.deleteCommand,sync.deleteId());', advance)
        self.assertLess(advance.index('sync.tableSent=true;publishTodoFullSync();'), advance.index('sendBusiness("SCHEDULE_TODO",6'))

    def test_reconcile_follows_the_official_model(self):
        activity = src('app/src/SdkProbeActivity.java')
        rec = method(activity, 'private TodoFullSync.Plan reconcileTodos(')
        # Unknown glasses items are adopted before the phone list overwrites them.
        self.assertIn('store.adopt(eventId,g.getString("title")', rec)
        # A completion on the glasses completes the phone item; content comes from the phone.
        self.assertIn('if(LabTodoSync.integer(g,"status")==1&&!item.completed()){store.setCompleted(item.id,true);', rec)
        # Completed on the phone: never in the table, but known to read-back (review 09-23 .71).
        self.assertIn('if(item.completed()){Long done=eventOf.get(item.id);if(done!=null)completedHere.add(done);continue;}', rec)
        self.assertIn('return new TodoFullSync.Plan(items,gone,adopted,completed,completedHere);', rec)
        # Deleted on the phone: never adopted back, and collected for the delete command.
        self.assertLess(rec.index('if(deleted.contains(Long.toString(eventId)))continue;'), rec.index('store.adopt('))
        change = method(activity, 'private void glassesCompletedTodo(')
        self.assertIn('ConnectionService.requestTodoSync(this,2000);', change)
        inbound = activity[activity.index('BusinessEnvelope todoWire=null;'):]
        # Decoding cannot end the session; a type 4 is consumed (returns) before any running round sees it.
        self.assertIn('try{todoWire=BusinessEnvelope.decode((byte[])message.get("payload"));}catch(Exception e){', inbound[:800])
        self.assertIn('if(todoWire!=null&&todoWire.type==4){try{glassesCompletedTodo(new JSONObject(todoWire.json));}catch(Exception e){stage("todo_glasses_change_failed",e.getClass().getSimpleName());}return;}', inbound[:800])
        # Only completions from the glasses change the phone list.
        self.assertIn('if(body.optInt("eventType",-1)!=1||status!=1){', change)
        self.assertIn('store.setCompleted(local,true)', change)
        # Adopted-but-unlinked rows still take a glasses completion.
        self.assertIn('// Also for a row adopt() found already there without a link', rec)

    def test_sync_runs_after_ready_and_after_local_changes(self):
        service = src('app/src/ConnectionService.java')
        self.assertIn('if(becameReady)requestTodoSync(service,15000);', method(service, 'static void connectionReady('))
        self.assertIn('client.fullSync(', method(service, 'private void runTodoSync()'))
        page = src('app/src/TodoActivity.java')
        self.assertIn('syncGlasses();', method(page, 'private void refreshAfterSave()'))
        self.assertIn('glasses.fullSync(callback());', method(page, 'private void syncGlasses()'))
        delete = method(page, 'private void confirmDelete(')
        self.assertLess(delete.index('links.getString(address+"/"+item.id,null)'), delete.index('store.delete(item.id);'))
        self.assertIn('address+"/deleted"', delete)
        self.assertIn("'lab-todo-full-sync'", src('session.py'))
