package dev.xr.rayneo.probe;

import org.json.JSONObject;

/** Version adapter for the official 1.0.4 wakeup_config contract. */
final class RayNeoWakeSettings {
    final int headupSwitch, headupDegree, crownSwitch;
    private RayNeoWakeSettings(int enabled, int degrees, int crown) {
        headupSwitch=enabled; headupDegree=degrees; crownSwitch=crown;
    }
    private static int integer(Object value, int maximum) {
        if (!(value instanceof Number)) return -1;
        double n=((Number)value).doubleValue();
        return Double.isNaN(n)||n<0||n>maximum||n!=Math.rint(n)?-1:(int)n;
    }
    static RayNeoWakeSettings read(JSONObject values, String prefix) {
        if (values==null) return null;
        int enabled=integer(values.opt(prefix+"headupSwitch"),1);
        int degrees=integer(values.opt(prefix+"headupDegree"),90);
        int crown=integer(values.opt(prefix+"crownSwitch"),3);
        return enabled<0||degrees<0||crown<0?null:new RayNeoWakeSettings(enabled,degrees,crown);
    }
    RayNeoWakeSettings withHeadUpDegrees(int degrees) {
        if (degrees<0||degrees>90) throw new IllegalArgumentException("Unsupported head-up angle");
        return new RayNeoWakeSettings(headupSwitch,degrees,crownSwitch);
    }
    JSONObject snapshot() throws Exception {
        return new JSONObject().put("headupSwitch",headupSwitch)
            .put("headupDegree",headupDegree).put("crownSwitch",crownSwitch);
    }
    JSONObject payload() throws Exception {
        JSONObject data=new JSONObject().put("headup_switch",headupSwitch)
            .put("headup_degree",headupDegree).put("crown_switch",crownSwitch);
        return new JSONObject().put("cmd","wakeup_config").put("payload",
            new JSONObject().put("value",0).put("mode",0).put("data",data.toString()));
    }
    boolean same(RayNeoWakeSettings other) {
        return other!=null&&headupSwitch==other.headupSwitch
            &&headupDegree==other.headupDegree&&crownSwitch==other.crownSwitch;
    }
}
