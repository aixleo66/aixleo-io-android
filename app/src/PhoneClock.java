package dev.xr.rayneo.probe;

import java.util.TimeZone;
import org.json.JSONObject;

/** Official sync_time shape: UTC decimal seconds string, offset integer seconds, zone name. */
final class PhoneClock {
    static JSONObject payload(long now,TimeZone zone)throws Exception{
        JSONObject data=new JSONObject().put("utc",Long.toString(now/1000)).put("offset",zone.getOffset(now)/1000).put("timezone",zone.getID());
        return new JSONObject().put("cmd","sync_time").put("payload",new JSONObject().put("value",0).put("mode",0).put("data",data.toString()));
    }
    static String basis(long now,long elapsed,TimeZone zone){return zone.getID()+"/"+zone.getOffset(now);}
}
