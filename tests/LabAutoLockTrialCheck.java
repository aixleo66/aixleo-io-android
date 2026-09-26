package dev.xr.rayneo.probe;
import java.util.*;
import org.json.JSONObject;
public final class LabAutoLockTrialCheck {
    static final class Port implements LabAutoLockTrial.Port {
        final List<String> calls=new ArrayList<>();
        boolean pending, saveOk=true, clearOk=true;
        public void query(String id){calls.add("query:"+id);}
        public void write(String id,int value){check(pending,"write before journal");calls.add("write:"+id+":"+value);}
        public boolean save(int original,int target){calls.add("save:"+original+":"+target);if(saveOk)pending=true;return saveOk;}
        public boolean clear(){if(clearOk)pending=false;return clearOk;}
    }
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static LabAutoLockTrial baseline(Port p,Object value){
        LabAutoLockTrial t=new LabAutoLockTrial("one",p);t.start(0);t.read("one:baseline",true,value,1);return t;
    }
    static void restored(LabAutoLockTrial t,int value,long now){
        t.sent("auto-lock-restore-one",true,now);t.read("one:restore",true,value,now+1);
    }
    public static void main(String[] args)throws Exception{
        for(Object invalid:new Object[]{true,false,5.5,Double.NaN,Double.POSITIVE_INFINITY,"5",0,121}){
            Port p=new Port();LabAutoLockTrial t=baseline(p,invalid);
            check(t.done&&!p.pending&&p.calls.size()==1,"invalid baseline wrote setting");
        }
        Port missing=new Port();check(baseline(missing,null).done&&!missing.pending,"missing baseline");
        Port noSave=new Port();noSave.saveOk=false;
        check(baseline(noSave,5).done&&noSave.calls.size()==2,"journal failure wrote setting");
        Port p=new Port();LabAutoLockTrial t=baseline(p,5);
        check(t.original==5&&t.target==10&&p.pending,"wrong baseline/target");
        t.read("one:baseline",true,15,2);check(t.original==5,"baseline consumed twice");
        t.sent("auto-lock-target-other",true,3);check(t.phase.equals("target_write"),"foreign callback consumed");
        t.sent("auto-lock-target-one",true,4);t.read("one:target",true,10,5);
        t.tick(45004);check(t.phase.equals("holding"),"observation window ended early");
        t.tick(45005);restored(t,5,45006);
        check(t.passed()&&!p.pending&&t.baselineRead==5&&t.targetRead==10&&t.restoreRead==5,"roundtrip failed");
        Port p10=new Port();check(baseline(p10,10).target==15,"target unchanged");
        Port fail=new Port();LabAutoLockTrial f=baseline(fail,5);f.sent("auto-lock-target-one",false,2);restored(f,5,3);
        check(f.restored&&!fail.pending&&!f.passed(),"failed target became pass or skipped restore");
        Port mismatch=new Port();LabAutoLockTrial m=baseline(mismatch,5);m.sent("auto-lock-target-one",true,2);m.read("one:target",true,15,3);restored(m,10,4);
        check(m.done&&mismatch.pending&&!m.restored,"mismatch cleared recovery");
        Port late=new Port();LabAutoLockTrial l=baseline(late,5);l.tick(12001);restored(l,5,12002);
        check(l.done&&l.ambiguous&&late.pending&&!l.restored,"late target risk cleared journal");
        Port lost=new Port();LabAutoLockTrial interrupted=baseline(lost,5);interrupted.interrupt();
        check(interrupted.done&&lost.pending,"interrupt lost receipt");
        LabAutoLockTrial repair=new LabAutoLockTrial("one",lost,5,10);repair.start(0);restored(repair,5,1);
        check(repair.passed()&&!lost.pending,"explicit recovery failed");
        Port clearFail=new Port();clearFail.pending=true;clearFail.clearOk=false;
        LabAutoLockTrial c=new LabAutoLockTrial("one",clearFail,5,10);c.start(0);restored(c,5,1);
        check(c.restored&&!c.passed()&&c.journalPending,"journal clear failure hidden");
        JSONObject body=LabAutoLockTrial.payload(10).getJSONObject("payload");
        check(body.length()==3&&body.opt("mode")==JSONObject.NULL&&body.opt("data")==JSONObject.NULL,"explicit nulls missing");
        System.out.println("auto lock baseline, roundtrip, failures and restoration checks passed");
    }
}
