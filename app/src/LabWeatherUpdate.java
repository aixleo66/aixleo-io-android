package dev.xr.rayneo.probe;

import org.json.JSONObject;

/** Transport completion and uncorrelated business observations are deliberately separate. */
final class LabWeatherUpdate {
    final String id;
    final WeatherReading reading;
    final JSONObject payload;
    final long deadline;
    boolean done,ack;
    String issue="";
    LabWeatherUpdate(String id,JSONObject source,long wall,long elapsed)throws Exception{
        this.id=id;reading=WeatherReading.parse(source,wall);payload=reading.payload(wall);deadline=elapsed+12000;
    }
    void sent(boolean ok,long now){if(done)return;if(now>=deadline){tick(now);return;}ack=ok;done=true;if(!ok)issue="send_failed";}
    void tick(long now){if(!done&&now>=deadline){done=true;issue="send_ack_timeout";}}
    JSONObject receipt()throws Exception{return new JSONObject().put("command_id",id).put("source",reading.source).put("payload",payload)
        .put("send_ack",ack).put("phase",done?(ack?"transport_sent":"failed"):"pending").put("issue",issue)
        .put("readback_verified",false).put("lens_observed",false);}
}
