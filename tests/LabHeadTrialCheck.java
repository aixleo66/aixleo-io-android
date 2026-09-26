package dev.xr.rayneo.probe;
import java.util.*;
import org.json.JSONObject;

public final class LabHeadTrialCheck {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static RayNeoHeadSettings value(Object enabled,Object mode)throws Exception{
        return RayNeoHeadSettings.read(new JSONObject().put("enabled",enabled).put("mode",mode),"");
    }
    static final class Port implements LabHeadTrial.Port {
        boolean pending;
        final List<RayNeoHeadSettings> writes=new ArrayList<>();
        public void query(String id){}
        public void write(String id,RayNeoHeadSettings setting){check(pending,"write before saved receipt");writes.add(setting);}
        public boolean save(RayNeoHeadSettings original,RayNeoHeadSettings target){pending=true;return true;}
        public boolean clear(){pending=false;return true;}
    }
    static LabHeadTrial baseline(Port p,RayNeoHeadSettings initial){
        LabHeadTrial t=new LabHeadTrial("one",p);t.start(0);t.read("one:baseline",true,initial,1);return t;
    }
    public static void main(String[] args)throws Exception{
        for(Object bad:new Object[]{null,true,"1",1.5,-1,2,Double.NaN,Double.POSITIVE_INFINITY}){
            check(value(bad,0)==null&&value(1,bad)==null,"unknown value normalized");
        }
        check(RayNeoHeadSettings.read(new JSONObject().put("enabled",1),"")==null,"missing mode defaulted");
        for(int enabled:new int[]{0,1}){
            RayNeoHeadSettings original=value(enabled,0),target=value(enabled,1);
            check(original.alternateMode().same(target),"enabled changed with mode");
            JSONObject payload=target.payload().getJSONObject("payload");
            check(payload.optInt("value")==enabled&&payload.optInt("mode")==1,"value and mode reversed");
            check(payload.opt("data") instanceof String&&payload.getString("data").isEmpty(),"data must be empty string");
            Port p=new Port();LabHeadTrial t=baseline(p,original);
            t.sent("crown-target-one",true,2);check(t.phase.equals("target_write"),"cross-trial callback accepted");
            t.sent("head-target-one",true,3);t.read("one:target",true,target,4);
            t.tick(3003);check(t.phase.equals("holding"),"early restore");
            t.tick(3004);t.sent("head-restore-one",true,3005);t.read("one:restore",true,original,3006);
            check(t.passed()&&!p.pending&&p.writes.size()==2&&p.writes.get(1).same(original),"roundtrip failed");
        }
        Port missing=new Port();check(baseline(missing,null).done&&missing.writes.isEmpty(),"missing baseline wrote");
        Port changed=new Port();LabHeadTrial t=baseline(changed,value(1,0));
        t.sent("head-target-one",true,2);t.read("one:target",true,value(0,1),3);
        check(t.phase.equals("restore_write"),"enabled mismatch not detected");
        t.sent("head-restore-one",true,4);t.read("one:restore",true,value(1,0),5);
        check(t.restored&&!t.passed()&&!changed.pending,"target mismatch promoted to pass");
        System.out.println("head mode, enabled preservation, strict encoding and roundtrip checks passed");
    }
}
