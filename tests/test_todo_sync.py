from pathlib import Path
import tempfile
import unittest
import lab

class TodoSyncChecks(unittest.TestCase):
    def test_single_write_preserves_64bit_identity_and_other_rows(self):
        java=r'''package dev.xr.rayneo.probe;
import org.json.*;
public final class TodoSyncCheck {
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 static JSONObject item(long id,String title)throws Exception{return new JSONObject().put("eventType",1).put("eventID",id).put("title",title).put("isImportant",JSONObject.NULL).put("status",0).put("createTime",1700000000123L).put("lastModifiedTime",1700000000L);}
 static JSONObject page(JSONObject...rows)throws Exception{JSONArray a=new JSONArray();for(JSONObject r:rows)a.put(r);return new JSONObject().put("todoTotal",rows.length).put("isLastBatch",true).put("dataList",a);}
 static void before(LabTodoSync s,JSONObject...rows)throws Exception{s.sent(s.readId(),true,1);s.reply(16,page(rows),2);}
 static void after(LabTodoSync s,JSONObject...rows)throws Exception{s.writeSent=true;s.sent(s.writeId(),true,3);check(!s.passed());s.advance(503);s.sent(s.readId(),true,504);s.reply(16,page(rows),505);}
 public static void main(String[]args)throws Exception{
  long id=285830507325763586L;JSONObject old=item(id,"旧标题"),other=item(id+1,"保留");
  LabTodoSync s=new LabTodoSync("rename","rename",id,"新标题",0,0,1800000000L,0);before(s,old,other);
  check(s.phase.equals("writing")&&!s.passed()&&s.payload.getLong("eventID")==id&&s.payload.get("isImportant")==JSONObject.NULL);
  check(s.payload.getLong("createTime")==old.getLong("createTime")&&old.getString("title").equals("旧标题"));
  after(s,s.payload,other);check(s.passed()&&s.after.length()==2);
  s=new LabTodoSync("completed-remove","update_local",id,"本地修改",old.getLong("createTime"),1,1800000000L,0);before(s,old,other);
  after(s,other);check(s.passed()&&s.after.length()==1&&s.effect.equals("removed_from_pending_list"));
  s=new LabTodoSync("completed-other-changed","update_local",id,"本地修改",old.getLong("createTime"),1,1800000000L,0);before(s,old,other);
  after(s,item(id+1,"changed"));check(!s.passed()&&s.issue.equals("other_item_changed"));
  s=new LabTodoSync("pending-missing","update_local",id,"本地修改",old.getLong("createTime"),0,1800000000L,0);before(s,old,other);
  after(s,other);check(!s.passed()&&s.issue.equals("target_readback_mismatch"));
  s=new LabTodoSync("completed-new","push_local",id+2,"已完成",1700000000999L,1,1800000000L,0);before(s,old,other);
  check(s.done&&!s.writeSent&&!s.passed()&&s.issue.equals("completed_local_not_in_pending_list"));
  s=new LabTodoSync("changed","rename",id,"新标题",0,0,1800000000L,0);before(s,old,other);
  after(s,s.payload,item(id+1,"意外变更"));check(!s.passed()&&s.issue.equals("other_item_changed"));
  s=new LabTodoSync("missing","rename",id,"新标题",0,0,1800000000L,0);before(s,other);check(s.done&&!s.writeSent&&s.issue.equals("target_not_found"));
  s=new LabTodoSync("new","push_local",id+2,"新增",1700000000999L,0,1800000000L,0);before(s,old,other);after(s,old,other,s.payload);check(s.passed()&&s.after.length()==3);
  s=new LabTodoSync("update","update_local",id,"本地修改",old.getLong("createTime"),1,1800000000L,0);before(s,old,other);
  check(s.phase.equals("writing")&&s.payload.getLong("eventID")==id&&s.payload.getLong("status")==1&&s.payload.getLong("createTime")==old.getLong("createTime"));
  after(s,s.payload,other);check(s.passed()&&s.after.length()==2);
  s=new LabTodoSync("same","update_local",id,"旧标题",old.getLong("createTime"),0,1800000000L,0);before(s,old,other);
  check(s.passed()&&!s.writeSent&&s.after.length()==2);
  s=new LabTodoSync("identity","update_local",id,"本地修改",old.getLong("createTime")+1,1,1800000000L,0);before(s,old,other);
  check(s.done&&!s.writeSent&&s.issue.equals("mapped_target_identity_mismatch"));
  s=new LabTodoSync("collision","push_local",id,"新增",1700000000999L,0,1800000000L,0);before(s,old);check(s.done&&!s.writeSent&&s.issue.equals("new_id_already_exists_no_overwrite"));
  s=new LabTodoSync("dropped","rename",id,"新标题",0,0,1800000000L,0);before(s,old,other);after(s,s.payload);check(!s.passed());
  s=new LabTodoSync("failed","rename",id,"新标题",0,0,1800000000L,0);before(s,old);s.writeSent=true;s.sent(s.writeId(),false,4);check(s.done&&!s.passed()&&s.phase.equals("write_unconfirmed"));
  s=new LabTodoSync("timeout","rename",id,"新标题",0,0,1800000000L,0);s.advance(45000);check(s.done&&!s.writeSent);
  boolean rejected=false;try{LabTodoSync.copyItem(item(id,"x").put("eventID",(double)id));}catch(IllegalArgumentException e){rejected=true;}check(rejected);
  s=new LabTodoSync("duplicate","rename",id,"新标题",0,0,1800000000L,0);rejected=false;try{before(s,old,old);}catch(IllegalArgumentException e){rejected=true;}check(rejected&&!s.writeSent);
  System.out.println("single-item sync and preservation checks passed");
 }
}'''
        s=lab.settings()
        with tempfile.TemporaryDirectory() as tmp:
            source=Path(tmp)/'TodoSyncCheck.java';source.write_text(java,encoding='utf-8')
            lab.command([lab.tool(s,'javac'),'-encoding','UTF-8','-d',tmp,source,lab.ROOT/'app/src/LabTodoSync.java',lab.ROOT/'app/src/LabTodoQuery.java',*list((lab.ROOT/'tests/stubs/org/json').glob('*.java'))])
            result=lab.command([lab.tool(s,'java'),'-cp',tmp,'dev.xr.rayneo.probe.TodoSyncCheck'])
            self.assertIn(b'single-item sync and preservation checks passed',result.stdout)
