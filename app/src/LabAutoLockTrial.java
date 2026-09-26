package dev.xr.rayneo.probe;

import org.json.JSONObject;

/** One setting, one fresh baseline, one bounded restoration. Main-looper only. */
final class LabAutoLockTrial {
    interface Port {
        void query(String id) throws Exception;
        void write(String id, int seconds) throws Exception;
        boolean save(int original, int target);
        boolean clear();
    }
    final String id;
    final boolean restoreOnly;
    final Port port;
    int original=-1, target=-1, baselineRead=-1, targetRead=-1, restoreRead=-1;
    long deadline, holdUntil;
    boolean done, journalPending, targetSent, restoreSent, restored, ambiguous;
    String phase="prepared", issue="", request="";
    LabAutoLockTrial(String id,Port port){this.id=id;this.port=port;restoreOnly=false;}
    LabAutoLockTrial(String id,Port port,int original,int target){
        this.id=id;this.port=port;this.restoreOnly=true;this.original=original;this.target=target;
        if(seconds(original)<0||seconds(target)<0)throw new IllegalArgumentException("Invalid restore receipt");
        journalPending=true;
    }
    static int seconds(Object value){
        if(!(value instanceof Number))return -1;
        double n=((Number)value).doubleValue();
        for(int allowed:new int[]{5,10,15,25,40,60,120})if(n==allowed)return allowed;
        return -1;
    }
    static JSONObject payload(int seconds)throws Exception{
        if(seconds(seconds)<0)throw new IllegalArgumentException("Unsupported idle timeout");
        return new JSONObject().put("cmd","auto_lock_time").put("payload",new JSONObject()
            .put("value",seconds).put("mode",JSONObject.NULL).put("data",JSONObject.NULL));
    }
    boolean owns(String requestId){return requestId.equals("auto-lock-target-"+id)||requestId.equals("auto-lock-restore-"+id);}
    void start(long now){if(restoreOnly)restore(now);else query("baseline",now);}
    private void fail(String why){if(issue.isEmpty())issue=why;}
    private void end(String state){done=true;phase=state;}
    private void query(String step,long now){
        phase=step+"_query";request=id+":"+step;deadline=now+12000;
        try{port.query(request);}catch(Exception e){read(request,false,null,now);}
    }
    private void write(boolean restoring,long now){
        phase=restoring?"restore_write":"target_write";deadline=now+12000;
        try{port.write("auto-lock-"+(restoring?"restore-":"target-")+id,restoring?original:target);}
        catch(Exception e){sent("auto-lock-"+(restoring?"restore-":"target-")+id,false,now);}
    }
    private void restore(long now){if(journalPending)write(true,now);else end("failed_without_write");}
    void sent(String requestId,boolean ok,long now){
        if(done||!owns(requestId))return;
        if(requestId.equals("auto-lock-target-"+id)&&phase.equals("target_write")){
            targetSent=ok;
            if(ok)query("target",now);else{fail("target_send_failed");restore(now);}
        }else if(requestId.equals("auto-lock-restore-"+id)&&phase.equals("restore_write")){
            restoreSent=ok;
            if(ok)query("restore",now);else{fail("restore_send_failed");end("restore_unconfirmed");}
        }
    }
    void read(String queryId,boolean ok,Object value,long now){
        if(done||!phase.endsWith("_query")||!request.equals(queryId))return;
        int observed=seconds(value);
        String step=phase;request=""; // Consume this exact query once, before starting the next.
        if(step.equals("baseline_query")){
            if(!ok||observed<0){fail("baseline_unavailable");end("failed_without_write");return;}
            original=baselineRead=observed;target=original==10?15:10;
            if(!port.save(original,target)){fail("journal_save_failed");end("failed_without_write");return;}
            journalPending=true;write(false,now);
        }else if(step.equals("target_query")){
            targetRead=observed;
            if(!ok){ambiguous=true;fail("target_query_failed");restore(now);}
            else if(observed!=target){fail("target_readback_mismatch");restore(now);}
            // Give the observer time to wake the lens, then leave it idle.
            else{phase="holding";holdUntil=now+45000;}
        }else{
            restoreRead=observed;
            if(ok&&observed==original&&restoreSent&&!ambiguous){
                restored=true;
                if(port.clear()){journalPending=false;end("restored");}
                else{fail("journal_clear_failed");end("restored_journal_pending");}
            }else{fail(ambiguous?"new_session_restore_required":"restore_readback_unconfirmed");end("restore_unconfirmed");}
        }
    }
    void tick(long now){
        if(done)return;
        if(phase.equals("holding")){if(now>=holdUntil)restore(now);return;}
        if(now<deadline)return;
        if(phase.equals("baseline_query")){fail("baseline_timeout");end("failed_without_write");}
        else if(phase.equals("target_query")||phase.equals("target_write")){
            ambiguous=true;fail("target_timeout");restore(now);
        }else{fail("restore_timeout");end("restore_unconfirmed");}
    }
    void interrupt(){if(!done){fail("session_interrupted");end(journalPending?"restore_unconfirmed":"failed_without_write");}}
    boolean passed(){return done&&restored&&!journalPending&&issue.isEmpty()&&(restoreOnly||(targetSent&&targetRead==target));}
}
