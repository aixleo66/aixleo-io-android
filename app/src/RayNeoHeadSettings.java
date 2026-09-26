package dev.xr.rayneo.probe;

import org.json.JSONObject;

/** RayNeo 1.0.4 head_gestures encoding, independent from product UI. */
final class RayNeoHeadSettings {
    enum Confirmation { NOD(0), SHAKE(1); final int wire; Confirmation(int wire){this.wire=wire;} }
    final int enabled, mode;
    private RayNeoHeadSettings(int enabled,int mode){this.enabled=enabled;this.mode=mode;}
    private static int bit(Object value){
        if(!(value instanceof Number))return -1;
        double n=((Number)value).doubleValue();return n==0?0:n==1?1:-1;
    }
    static RayNeoHeadSettings read(JSONObject values,String prefix){
        if(values==null)return null;
        int enabled=bit(values.opt(prefix+"enabled")),mode=bit(values.opt(prefix+"mode"));
        return enabled<0||mode<0?null:new RayNeoHeadSettings(enabled,mode);
    }
    RayNeoHeadSettings withConfirmation(Confirmation confirmation){return new RayNeoHeadSettings(enabled,confirmation.wire);}
    RayNeoHeadSettings alternateMode(){return withConfirmation(mode==0?Confirmation.SHAKE:Confirmation.NOD);}
    JSONObject snapshot()throws Exception{return new JSONObject().put("enabled",enabled).put("mode",mode);}
    JSONObject payload()throws Exception{
        return new JSONObject().put("cmd","head_gestures").put("payload",new JSONObject()
            .put("value",enabled).put("mode",mode).put("data",""));
    }
    boolean same(RayNeoHeadSettings other){return other!=null&&enabled==other.enabled&&mode==other.mode;}
}
