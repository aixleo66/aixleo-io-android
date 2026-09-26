package dev.xr.rayneo.probe;
import java.util.*;
import org.json.JSONObject;
public final class LabCrownTrialCheck {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static RayNeoCrownSettings value(Object direction,Object twice,Object hold)throws Exception{
        return RayNeoCrownSettings.read(new JSONObject().put("direction",direction).put("double",twice).put("longPress",hold),"");
    }
    static final class Port implements LabCrownTrial.Port {
        final List<RayNeoCrownSettings> writes=new ArrayList<>();
        boolean pending, saveOk=true;
        public void query(String id){}
        public void write(String id,RayNeoCrownSettings value){check(pending,"write before journal");writes.add(value);}
        public boolean save(RayNeoCrownSettings original,RayNeoCrownSettings target){if(saveOk)pending=true;return saveOk;}
        public boolean clear(){pending=false;return true;}
    }
    static LabCrownTrial baseline(Port p,RayNeoCrownSettings value){
        LabCrownTrial t=new LabCrownTrial("one",p);t.start(0);t.read("one:baseline",true,value,1);return t;
    }
    static void restored(LabCrownTrial t,RayNeoCrownSettings value,long now){
        t.sent("crown-restore-one",true,now);t.read("one:restore",true,value,now+1);
    }
    public static void main(String[] args)throws Exception{
        RayNeoCrownSettings original=value(1,3,1), target=value(0,3,1);
        for(Object bad:new Object[]{null,true,"1",1.5,Double.NaN,Double.POSITIVE_INFINITY,-1,2})
            check(value(bad,3,1)==null,"invalid direction accepted");
        check(value(1,10,1)==null&&value(1,3,6)==null,"unsupported action accepted");
        check(RayNeoCrownSettings.read(new JSONObject().put("direction",1),"")==null,"missing baseline filled with zero");
        check(original.alternateDirection().same(target)&&target.alternateDirection().same(original),"siblings lost");
        check(original.withDirection(RayNeoCrownSettings.Direction.NATURAL).direction==1,"ordinal leaked");
        JSONObject body=target.payload().getJSONObject("payload");
        check(body.optInt("value")==0&&body.optInt("mode")==0&&body.opt("data") instanceof String,"wrong payload types");
        check(body.getString("data").equals("{\"double\":3,\"longPress\":1}"),"compound wire keys lost");
        Port missing=new Port();check(baseline(missing,null).done&&missing.writes.isEmpty(),"missing baseline wrote");
        Port noSave=new Port();noSave.saveOk=false;check(baseline(noSave,original).done&&noSave.writes.isEmpty(),"journal failure wrote");
        Port p=new Port();LabCrownTrial t=baseline(p,original);
        t.read("one:baseline",true,target,2);check(t.original.same(original),"duplicate baseline changed original");
        t.sent("crown-target-other",true,2);check(t.phase.equals("target_write"),"foreign callback consumed");
        t.sent("crown-target-one",true,3);t.read("one:target",true,target,4);
        t.tick(45003);check(t.phase.equals("holding"),"observation window shortened");
        t.tick(45004);restored(t,original,45005);
        check(t.passed()&&!p.pending&&p.writes.size()==2&&p.writes.get(1).same(original),"roundtrip failed");
        Port altered=new Port();LabCrownTrial a=baseline(altered,original);
        a.sent("crown-target-one",true,2);a.read("one:target",true,value(0,0,1),3);
        check(a.phase.equals("restore_write"),"sibling change accepted");restored(a,original,4);
        check(!a.passed()&&a.restored&&!altered.pending,"bad target became pass");
        Port mismatch=new Port();LabCrownTrial m=baseline(mismatch,original);m.sent("crown-target-one",false,2);
        restored(m,value(1,3,0),3);check(mismatch.pending&&!m.restored,"restore sibling mismatch ignored");
        Port late=new Port();LabCrownTrial l=baseline(late,original);l.tick(12001);restored(l,original,12002);
        check(l.ambiguous&&late.pending&&!l.passed(),"late write risk erased");
        LabCrownTrial repair=new LabCrownTrial("one",late,original,target);repair.start(0);restored(repair,original,1);
        check(repair.passed()&&!late.pending,"explicit restore failed");
        System.out.println("crown encoding, sibling preservation, roundtrip and restoration checks passed");
    }
}
