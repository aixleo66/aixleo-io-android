package dev.xr.rayneo.probe;
public class LabFirmwareQueryCheck {
 static void check(boolean value){if(!value)throw new AssertionError();}
 public static void main(String[] args){
  LabFirmwareQuery q=new LabFirmwareQuery("a",0);q.reply(2,"1.2",10);check(q.ignored==1&&!q.done);q.reply(1,"1.2.3",20);check(!q.done);q.sent(true,30);check(q.passed());q.reply(1,"9.9",40);check(q.version.equals("1.2.3"));
  q=new LabFirmwareQuery("b",0);q.sent(true,1);q.tick(12000);check(!q.passed()&&q.issue.equals("reply_timeout"));q.reply(1,"1.2",12001);check(q.version.isEmpty());
  q=new LabFirmwareQuery("c",0);q.reply(1,"1.2",2);q.sent(false,3);check(!q.passed());
  for(Object value:new Object[]{null,17,"","bad\nvalue","x".repeat(129)}){q=new LabFirmwareQuery("d",0);q.sent(true,1);q.reply(1,value,2);check(q.done&&!q.passed());}
  q=new LabFirmwareQuery("e",0);q.sent(true,12000);check(q.issue.equals("send_ack_timeout"));
  System.out.println("firmware query checks passed");
 }
}
