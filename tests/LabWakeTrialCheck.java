package dev.xr.rayneo.probe;
import org.json.JSONObject;
import java.util.*;

public final class LabWakeTrialCheck {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static RayNeoWakeSettings value(int enabled,int angle,int crown)throws Exception{
        return RayNeoWakeSettings.read(new JSONObject().put("headupSwitch",enabled)
            .put("headupDegree",angle).put("crownSwitch",crown),"");
    }
    static final class Port implements LabWakeTrial.Port {
        boolean pending;
        List<RayNeoWakeSettings> writes=new ArrayList<>();
        public void query(String id){}
        public void write(String id,RayNeoWakeSettings value){check(pending,"missing journal");writes.add(value);}
        public boolean save(RayNeoWakeSettings original,RayNeoWakeSettings target){pending=true;return true;}
        public boolean clear(){pending=false;return true;}
    }
    public static void main(String[] args)throws Exception{
        for(int enabled:new int[]{0,1})for(int angle:new int[]{15,30})for(int crown:new int[]{0,1,2,3}){
            Port p=new Port();LabWakeTrial t=new LabWakeTrial("one",p);
            RayNeoWakeSettings original=value(enabled,angle,crown),target=value(enabled,angle==30?15:30,crown);
            t.start(0);t.read("one:baseline",true,original,1);
            check(p.writes.size()==1&&p.writes.get(0).same(target),"wrong target or unrelated fields changed");
            t.sent("head-target-one",true,2);check(t.phase.equals("target_write"),"cross-trial callback");
            t.sent("wake-target-one",true,3);t.read("one:target",true,target,4);
            t.tick(45003);check(t.phase.equals("holding"),"early restoration");
            t.tick(45004);t.sent("wake-restore-one",true,45005);t.read("one:restore",true,original,45006);
            check(t.passed()&&!p.pending&&p.writes.get(1).same(original),"roundtrip incomplete");
        }
        Port p=new Port();LabWakeTrial t=new LabWakeTrial("two",p);
        t.start(0);t.read("two:baseline",true,null,1);check(t.done&&p.writes.isEmpty(),"missing baseline wrote");
        p=new Port();t=new LabWakeTrial("three",p);RayNeoWakeSettings original=value(1,15,3);
        t.start(0);t.read("three:baseline",true,original,1);t.sent("wake-target-three",true,2);
        t.read("three:target",true,value(1,30,0),3);check(t.phase.equals("restore_write"),"crown mismatch ignored");
        t.sent("wake-restore-three",true,4);t.read("three:restore",true,original,5);
        check(t.restored&&!t.passed()&&!p.pending,"mismatch promoted to pass");
        for(int angle:new int[]{5,60}){
            p=new Port();t=new LabWakeTrial("compare",p,angle);
            original=value(1,15,3);RayNeoWakeSettings target=value(1,angle,3);
            t.start(0);t.read("compare:baseline",true,original,1);
            check(p.writes.get(0).same(target),"wrong comparison angle");
            t.sent("wake-target-compare",true,2);t.read("compare:target",true,target,3);
            t.tick(60002);check(t.phase.equals("holding"),"comparison window too short");
            t.tick(60003);t.sent("wake-restore-compare",true,60004);t.read("compare:restore",true,original,60005);
            check(t.passed()&&p.writes.get(1).same(original)&&!p.pending,"comparison failed to restore");
        }
        try{new LabWakeTrial("bad",new Port(),90);throw new AssertionError("arbitrary target allowed");}
        catch(IllegalArgumentException expected){}
        System.out.println("wake trial preservation and restoration checks passed");
    }
}
