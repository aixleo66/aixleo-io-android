package dev.xr.rayneo.probe;
import org.json.JSONObject;

public final class RayNeoWakeSettingsCheck {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static JSONObject values(Object enabled,Object angle,Object crown)throws Exception{
        return new JSONObject().put("headupSwitch",enabled).put("headupDegree",angle).put("crownSwitch",crown);
    }
    public static void main(String[] args)throws Exception{
        for(int enabled:new int[]{0,1})for(int crown:new int[]{0,1,2,3}){
            RayNeoWakeSettings original=RayNeoWakeSettings.read(values(enabled,15,crown),"");
            RayNeoWakeSettings target=original.withHeadUpDegrees(30);
            check(target.headupSwitch==enabled&&target.crownSwitch==crown,"unrelated setting overwritten");
            JSONObject envelope=target.payload();
            JSONObject p=envelope.getJSONObject("payload");
            check(envelope.getString("cmd").equals("wakeup_config"),"wrong command");
            check(p.optInt("value")==0&&p.optInt("mode")==0,"wrong outer values");
            check(p.opt("data") instanceof String,"data must be JSON string");
            JSONObject data=new JSONObject(p.getString("data"));
            check(data.length()==3&&data.optInt("headup_switch")==enabled
                &&data.optInt("headup_degree")==30&&data.optInt("crown_switch")==crown,"wire keys or values wrong");
            check(target.withHeadUpDegrees(15).same(original),"restore changed other fields");
            check(RayNeoWakeSettings.read(original.snapshot(),"").same(original),"receipt lost fields");
        }
        for(Object bad:new Object[]{null,true,"15",-1,91,1.5,Double.NaN,Double.POSITIVE_INFINITY})
            check(RayNeoWakeSettings.read(values(1,bad,0),"")==null,"invalid angle normalized");
        check(RayNeoWakeSettings.read(values(2,15,0),"")==null,"unknown switch normalized");
        check(RayNeoWakeSettings.read(values(1,15,4),"")==null,"unknown crown normalized");
        check(RayNeoWakeSettings.read(new JSONObject().put("headupDegree",15),"")==null,"missing baseline defaulted");
        check(RayNeoWakeSettings.read(values(1,0,0),"")!=null&&RayNeoWakeSettings.read(values(1,90,0),"")!=null,"sender range lost");
        RayNeoWakeSettings original=RayNeoWakeSettings.read(values(1,15,0),"");
        for(int bad:new int[]{-1,91}){
            try{original.withHeadUpDegrees(bad);throw new AssertionError("bad angle accepted");}
            catch(IllegalArgumentException expected){}
        }
        System.out.println("wake settings contract and preservation checks passed");
    }
}
