package dev.xr.rayneo.probe;

/** Serial read-only snapshot. The wire does not expose a proven request-ID echo. */
final class LabSettingsQuery {
    final String id;
    final long deadline;
    boolean sent, reply, done;
    int values, ignored;
    long replyAt=-1;
    String issue="";
    LabSettingsQuery(String id,long now){this.id=id;deadline=now+12000;}
    void sent(boolean ok,long now){
        if(done)return;
        tick(now);if(done)return;
        if(!ok){issue="send_failed";done=true;return;}
        sent=true;finish();
    }
    boolean reply(String business,int type,boolean generalObject,int validValues,long now){
        if(done)return false;
        tick(now);if(done)return false;
        if(!"LAUNCHER".equals(business)||type!=4||!generalObject||validValues<=0){ignored++;return false;}
        reply=true;replyAt=now;values=validValues;finish();return true;
    }
    private void finish(){if(sent&&reply)done=true;}
    void tick(long now){if(!done&&now>=deadline){done=true;issue=sent?"valid_reply_timeout":"send_callback_timeout";}}
    boolean passed(){return done&&issue.isEmpty()&&sent&&reply&&values>0;}
}
