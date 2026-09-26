package dev.xr.rayneo.probe;


/** One setting, one fresh baseline, one bounded restoration. Main-looper only. */
class LabSettingsTrial<T> {
    interface Policy<T> { T target(T original); boolean same(T left,T right); }
    final Policy<T> policy;
    final String prefix;
    final long holdMillis;
    interface Port<T> {
        void query(String id) throws Exception;
        void write(String id, T settings) throws Exception;
        boolean save(T original, T target);
        boolean clear();
    }
    final String id;
    final boolean restoreOnly;
    final Port<T> port;
    T original, target, baselineRead, targetRead, restoreRead;
    long deadline, holdUntil;
    boolean done, journalPending, targetSent, restoreSent, restored, ambiguous;
    String phase="prepared", issue="", request="";
    LabSettingsTrial(String id,Port<T> port,Policy<T> policy,String prefix,long holdMillis){
        this.id=id;this.port=port;this.policy=policy;this.prefix=prefix;this.holdMillis=holdMillis;restoreOnly=false;
    }
    LabSettingsTrial(String id,Port<T> port,Policy<T> policy,String prefix,long holdMillis,T original,T target){
        this.id=id;this.port=port;this.policy=policy;this.prefix=prefix;this.holdMillis=holdMillis;
        this.restoreOnly=true;this.original=original;this.target=target;
        if(original==null||target==null)throw new IllegalArgumentException("Invalid restore receipt");
        journalPending=true;
    }
    boolean owns(String requestId){return requestId.equals(prefix+"-target-"+id)||requestId.equals(prefix+"-restore-"+id);}
    void start(long now){if(restoreOnly)restore(now);else query("baseline",now);}
    private void fail(String why){if(issue.isEmpty())issue=why;}
    private void end(String state){done=true;phase=state;}
    private void query(String step,long now){
        phase=step+"_query";request=id+":"+step;deadline=now+12000;
        try{port.query(request);}catch(Exception e){read(request,false,null,now);}
    }
    private void write(boolean restoring,long now){
        phase=restoring?"restore_write":"target_write";deadline=now+12000;
        try{port.write(prefix+"-"+(restoring?"restore-":"target-")+id,restoring?original:target);}
        catch(Exception e){sent(prefix+"-"+(restoring?"restore-":"target-")+id,false,now);}
    }
    private void restore(long now){if(journalPending)write(true,now);else end("failed_without_write");}
    void sent(String requestId,boolean ok,long now){
        if(done||!owns(requestId))return;
        if(requestId.equals(prefix+"-target-"+id)&&phase.equals("target_write")){
            targetSent=ok;
            if(ok)query("target",now);else{fail("target_send_failed");restore(now);}
        }else if(requestId.equals(prefix+"-restore-"+id)&&phase.equals("restore_write")){
            restoreSent=ok;
            if(ok)query("restore",now);else{fail("restore_send_failed");end("restore_unconfirmed");}
        }
    }
    void read(String queryId,boolean ok,T value,long now){
        if(done||!phase.endsWith("_query")||!request.equals(queryId))return;
        T observed=value;
        String step=phase;request=""; // Consume this exact query once, before starting the next.
        if(step.equals("baseline_query")){
            if(!ok||observed==null){fail("baseline_unavailable");end("failed_without_write");return;}
            original=baselineRead=observed;target=policy.target(original);
            if(!port.save(original,target)){fail("journal_save_failed");end("failed_without_write");return;}
            journalPending=true;write(false,now);
        }else if(step.equals("target_query")){
            targetRead=observed;
            if(!ok){ambiguous=true;fail("target_query_failed");restore(now);}
            else if(!policy.same(target,observed)){fail("target_readback_mismatch");restore(now);}
            // Holding duration is chosen by the concrete setting trial; readback is not user observation.
            else{phase="holding";holdUntil=now+holdMillis;}
        }else{
            restoreRead=observed;
            if(ok&&policy.same(original,observed)&&restoreSent&&!ambiguous){
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
    boolean passed(){return done&&restored&&!journalPending&&issue.isEmpty()&&(restoreOnly||(targetSent&&policy.same(target,targetRead)));}
}
