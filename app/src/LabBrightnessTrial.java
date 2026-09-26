package dev.xr.rayneo.probe;

/** Bounded Lab-only manual brightness probe; always restores the observed baseline 7. */
final class LabBrightnessTrial {
    static final int NONE=0, TARGET=1, QUERY_TARGET=2, RESTORE=3, QUERY_RESTORE=4;
    final String id;
    final boolean restoreOnly;
    final int target;
    final long holdMs;
    boolean targetSent, targetQuery, restoreSent, restoreQuery, targetAck, restoreAck;
    boolean targetObserved, restored, done, transportError;
    long targetAt, restoreAt;
    String phase="prepared";
    LabBrightnessTrial(String id, boolean restoreOnly){this(id,restoreOnly,8);}
    LabBrightnessTrial(String id, boolean restoreOnly,int target){
        if(target!=1&&target!=8&&target!=17)throw new IllegalArgumentException("Unsupported trial target");
        this.id=id;this.restoreOnly=restoreOnly;this.target=target;this.holdMs=target==8?5000:10000;
    }
    static boolean eligible(Object brightness,Object automatic,long age){
        return brightness instanceof Number && ((Number)brightness).doubleValue()==7
            && Boolean.FALSE.equals(automatic) && age>=0 && age<=10000;
    }
    int tick(long now){
        if(done)return NONE;
        if(!targetSent&&!restoreOnly){targetSent=true;targetAt=now;phase="target_sent";return TARGET;}
        if(!restoreSent&&(restoreOnly||transportError||now-targetAt>=holdMs)){
            restoreSent=true;restoreAt=now;phase="restore_sent";return RESTORE;
        }
        if(restoreSent){
            if(!restoreQuery&&now-restoreAt>=1000){restoreQuery=true;phase="restore_query";return QUERY_RESTORE;}
            if(now-restoreAt>=8000){done=true;phase="restore_unconfirmed";}
        }else if(!targetQuery&&now-targetAt>=1000){targetQuery=true;phase="target_query";return QUERY_TARGET;}
        return NONE;
    }
    void callback(int action,boolean ok){
        if(done)return;
        if(action==TARGET)targetAck=ok;
        if(action==RESTORE)restoreAck=ok;
        if(!ok)transportError=true;
    }
    void status(Object value){
        if(done||!(value instanceof Number))return;
        double level=((Number)value).doubleValue();
        if(restoreQuery&&restoreAck&&level==7){restored=true;done=true;phase="restored";}
        else if(!restoreSent&&targetQuery&&targetAck&&level==target)targetObserved=true;
    }
    void interrupt(){if(!done){done=true;phase="interrupted_restore_unconfirmed";}}
    boolean passed(){return restored&&(restoreOnly||(targetAck&&targetObserved&&!transportError));}
}
