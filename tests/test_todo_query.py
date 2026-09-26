from pathlib import Path
import tempfile
import unittest
import lab

class TodoQueryChecks(unittest.TestCase):
    def test_real_query_lifecycle_requires_ack_and_last_batch(self):
        s=lab.settings()
        java=r'''package dev.xr.rayneo.probe;
import org.json.*;
public final class TodoQueryCheck {
 static void check(boolean value){if(!value)throw new AssertionError();}
 static JSONObject page(int total,boolean last,String title)throws Exception{
  JSONArray rows=new JSONArray();if(title!=null)rows.put(new JSONObject().put("eventID",123).put("title",title).put("status",0));
  return new JSONObject().put("todoTotal",total).put("isLastBatch",last).put("dataList",rows);
 }
 public static void main(String[] args)throws Exception{
  JSONObject request=LabTodoQuery.request();check(request.length()==5&&request.optInt("queryType")==0&&request.optInt("eventType")==1&&request.optLong("lastSyncTime")==0&&request.optBoolean("needFullData")&&request.getJSONArray("eventIDList").length()==0);
  LabTodoQuery q=new LabTodoQuery("test",0);q.sent(true,1);check(!q.done);
  q.reply(16,page(2,false,"first"),2);check(!q.done&&q.items==1);
  q.reply(16,page(2,true,"last"),3);check(q.passed()&&q.replies.length()==2&&q.items==2);check(q.replies.getJSONObject(0).getJSONArray("dataList").getJSONObject(0).optString("title").equals("first"));
  q=new LabTodoQuery("empty",0);q.reply(16,page(0,true,null),2);check(!q.done);q.sent(true,3);check(q.passed()&&q.items==0);
  q=new LabTodoQuery("timeout",0);q.sent(true,1);q.reply(14,page(0,true,null),2);q.tick(15000);check(!q.passed()&&q.issue.equals("response_timeout")&&q.replies.length()==0);
  q=new LabTodoQuery("partial",0);q.sent(true,1);q.reply(16,page(2,true,"only-one"),2);check(!q.passed()&&q.issue.equals("incomplete_or_duplicate_items")&&q.replies.length()==1);
  q=new LabTodoQuery("error",0);q.sent(false,1);check(!q.passed()&&q.issue.equals("send_failed"));
  q=new LabTodoQuery("shape",0);q.sent(true,1);q.reply(16,new JSONObject().put("todoTotal",0),2);check(!q.passed()&&q.issue.equals("unrecognized_response_shape"));
  System.out.println("read-only query lifecycle checked");
 }
}'''
        with tempfile.TemporaryDirectory() as tmp:
            source=Path(tmp)/'TodoQueryCheck.java';source.write_text(java,encoding='utf-8')
            lab.command([lab.tool(s,'javac'),'-encoding','UTF-8','-d',tmp,source,lab.ROOT/'app/src/LabTodoQuery.java',*list((lab.ROOT/'tests/stubs/org/json').glob('*.java'))])
            result=lab.command([lab.tool(s,'java'),'-cp',tmp,'dev.xr.rayneo.probe.TodoQueryCheck'])
            self.assertIn(b'read-only query lifecycle checked',result.stdout)
