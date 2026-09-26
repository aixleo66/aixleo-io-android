package dev.xr.rayneo.probe;

/** One read-only version request per session. No upgrade command or retry exists here. */
final class LabFirmwareQuery {
    final String id;
    final long deadline;
    boolean ack, done;
    int replies, ignored;
    String version="", issue="", phase="pending";
    LabFirmwareQuery(String id,long now){this.id=id;deadline=now+12000;}
    void sent(boolean success,long now){
        if(done)return;
        if(now>=deadline){tick(now);return;}
        if(!success){issue="send_failed";done=true;phase="failed";return;}
        ack=true;finishIfReady();
    }
    void reply(int type,Object value,long now){
        if(done||now>=deadline){ignored++;tick(now);return;}
        if(type!=1){ignored++;return;}
        replies++;
        if(!(value instanceof String)||!((String)value).matches("[A-Za-z0-9][A-Za-z0-9._ +()/-]{0,127}")){
            issue="unrecognized_version_reply";done=true;phase="failed";return;
        }
        version=(String)value;finishIfReady();
    }
    void finishIfReady(){if(ack&&!version.isEmpty()){done=true;phase="reply_received";}}
    void tick(long now){if(!done&&now>=deadline){done=true;phase="failed";issue=ack?"reply_timeout":"send_ack_timeout";}}
    boolean passed(){return done&&issue.isEmpty()&&ack&&!version.isEmpty();}
}
