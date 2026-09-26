from pathlib import Path
import tempfile
import unittest
import lab


class VoiceTodoChecks(unittest.TestCase):
    def test_explicit_commands_actual_outcomes_and_cancel(self):
        s = lab.settings()
        java = r'''package dev.xr.rayneo.probe;
import java.util.*;
import org.json.*;
public final class VoiceTodoCheck {
 static void check(boolean v){if(!v)throw new AssertionError();}
 static class Store implements VoiceTodoAction.Store {
  List<VoiceTodoAction.Entry> items=new ArrayList<>();int writes;boolean fail;
  public VoiceTodoAction.Entry create(String command,String title){
   if(fail)throw new IllegalStateException();
   for(VoiceTodoAction.Entry e:items)if(e.id.equals(command))return e;
   VoiceTodoAction.Entry e=new VoiceTodoAction.Entry(command,title,false);items.add(e);writes++;return e;
  }
  public List<VoiceTodoAction.Entry> list(){return items;}
  public VoiceTodoAction.Entry complete(String id){
   if(fail)throw new IllegalStateException();
   for(int i=0;i<items.size();i++)if(items.get(i).id.equals(id)){
    VoiceTodoAction.Entry e=new VoiceTodoAction.Entry(id,items.get(i).title,true);items.set(i,e);writes++;return e;
   }throw new IllegalStateException();
  }
 }
 public static void main(String[] args)throws Exception{
  Store s=new Store();VoiceTodoAction.Check ready=()->{};
  check(VoiceTodoAction.parse("不要添加待办买菜")==null);
  check(VoiceTodoAction.parse("添加待办是什么意思？")==null);
  check(VoiceTodoAction.parse("添加待办功能怎么用。")==null);
  check(VoiceTodoAction.parse("对。不要添加待办买菜。")==null);
  check(VoiceTodoAction.parse("他说添加待办买菜。")==null);
  check(VoiceTodoAction.parse("对。 添加待办，测试语音待办。").title.equals("测试语音待办"));
  check(VoiceTodoAction.parse("好的，帮我查看待办。").kind.equals("list"));
  check(VoiceTodoAction.parse("请完成待办，测试语音待办。").kind.equals("complete"));
  check(VoiceTodoAction.parse("添加代办，找代办公司。").title.equals("找代办公司"));
  check(VoiceTodoAction.parse("添加任务，买牛奶。").title.equals("买牛奶"));
  check(VoiceTodoAction.parse("查看任务。").kind.equals("list"));
  check(VoiceTodoAction.parse("完成任务，买牛奶。").kind.equals("complete"));
  check(VoiceTodoAction.parse("测试代办，添加语音代办。")==null);
  check(VoiceTodoAction.parse("一加一等于几")==null);
  JSONObject r=VoiceTodoAction.parse("添加待办，测试语音待办。").execute(s,"one",ready);
  check(s.writes==1&&r.getJSONObject("action").optString("status").equals("succeeded"));
  check(r.optString("text").contains("不设提醒")&&!r.getJSONObject("action").optBoolean("glasses_synced"));
  r=VoiceTodoAction.parse("查看待办").execute(s,"two",ready);check(r.getJSONObject("action").optInt("pending_count")==1);
  r=VoiceTodoAction.parse("完成待办，测试语音").execute(s,"three",ready);check(s.writes==1&&r.getJSONObject("action").optString("status").equals("rejected"));
  r=VoiceTodoAction.parse("完成待办，测试语音待办").execute(s,"four",ready);check(s.writes==2&&s.items.get(0).completed);
  s.items.add(new VoiceTodoAction.Entry("duplicate","测试语音待办",false));
  r=VoiceTodoAction.parse("完成待办，测试语音待办").execute(s,"five",ready);check(s.writes==2&&r.getJSONObject("action").optString("status").equals("rejected"));
  s.fail=true;r=VoiceTodoAction.parse("添加待办失败").execute(s,"six",ready);
  check(r.getJSONObject("action").optString("status").equals("failed")&&!r.optString("text").contains("已记入"));
  try{VoiceTodoAction.parse("添加待办取消").execute(s,"seven",()->{throw new InterruptedException();});throw new AssertionError();}catch(InterruptedException expected){}
  check(s.writes==2);
  r=VoiceTodoAction.parse("添加待办").execute(s,"eight",ready);check(r.getJSONObject("action").optString("status").equals("rejected"));
  System.out.println("voice todo command outcomes checked");
 }
}'''
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / 'VoiceTodoCheck.java'
            source.write_text(java, encoding='utf-8')
            lab.command([lab.tool(s, 'javac'), '-encoding', 'UTF-8', '-d', tmp, source,
                         lab.ROOT / 'app/src/VoiceTodoAction.java', lab.ROOT / 'app/src/VoicePhrase.java',
                         *list((lab.ROOT / 'tests/stubs/org/json').glob('*.java'))])
            result = lab.command([lab.tool(s, 'java'), '-cp', tmp, 'dev.xr.rayneo.probe.VoiceTodoCheck'])
            self.assertIn(b'voice todo command outcomes checked', result.stdout)
