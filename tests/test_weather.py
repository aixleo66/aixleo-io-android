from pathlib import Path
import tempfile
import unittest
import lab

class WeatherChecks(unittest.TestCase):
    def test_weather_source_mapping_freshness_and_transport_boundary(self):
        java=r'''package dev.xr.rayneo.probe;
import org.json.*;
import java.util.TimeZone;
public final class WeatherCheck {
 static long NOW=1789640000000L;
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 static JSONObject source(int code,int day,double temp){return new JSONObject().put("source","Open-Meteo").put("location_source","android_approximate_location").put("fetched_at_ms",NOW).put("response",new JSONObject().put("current_units",new JSONObject().put("time","unixtime").put("temperature_2m","°C")).put("current",new JSONObject().put("time",NOW/1000).put("weather_code",code).put("is_day",day).put("temperature_2m",temp)));}
 static void rejects(JSONObject s)throws Exception{boolean bad=false;try{WeatherReading.parse(s,NOW);}catch(Exception e){bad=true;}check(bad);}
 public static void main(String[] args)throws Exception{
  check(WeatherReading.parse(source(0,1,23.6),NOW).temperature==24);
  check(WeatherReading.parse(source(0,0,-2.6),NOW).temperature==-3);
  int[] codes={0,1,3,45,61,71,95},icons={100,101,101,500,305,400,302};
  for(int i=0;i<codes.length;i++)check(WeatherReading.parse(source(codes[i],1,20),NOW).icon==icons[i]);
  check(WeatherReading.parse(source(0,0,20),NOW).icon==150);
  check(WeatherReading.parse(source(2,0,20),NOW).icon==151);
  rejects(source(100,1,20));rejects(source(0,2,20));rejects(source(0,1,Double.NaN));rejects(source(0,1,80));
  JSONObject bad=source(0,1,20);bad.getJSONObject("response").getJSONObject("current").remove("temperature_2m");rejects(bad);
  bad=source(0,1,20);bad.getJSONObject("response").getJSONObject("current").put("weather_code",4294967296L);rejects(bad);
  bad=source(0,1,20).put("fetched_at_ms",NOW-600001);rejects(bad);
  bad=source(0,1,20);bad.getJSONObject("response").getJSONObject("current").put("time",NOW/1000-7201);rejects(bad);
  bad=source(0,1,20);bad.getJSONObject("response").getJSONObject("current_units").put("temperature_2m","°F");rejects(bad);
  LabWeatherUpdate update=new LabWeatherUpdate("one",source(0,1,20),NOW,0);
  check(update.payload.getJSONObject("payload").get("data") instanceof String);
  check(update.payload.getJSONObject("payload").getString("ts").equals(Long.toString(NOW/1000)));
  update.sent(true,1);check(update.done&&update.ack&&!update.receipt().optBoolean("readback_verified")&&!update.receipt().optBoolean("lens_observed"));
  update=new LabWeatherUpdate("two",source(0,1,20),NOW,0);update.tick(12000);update.sent(true,12001);check(!update.ack&&update.issue.equals("send_ack_timeout"));
  JSONObject clock=PhoneClock.payload(NOW,TimeZone.getTimeZone("Asia/Shanghai"));String data=clock.getJSONObject("payload").getString("data");
  check(clock.getString("cmd").equals("sync_time")&&data.contains("\"utc\":\"1789640000\"")&&data.contains("\"offset\":28800")&&data.contains("Asia/Shanghai"));
  check(PhoneClock.payload(NOW,TimeZone.getTimeZone("America/New_York")).toString().contains("-14400"));
  System.out.println("weather and phone clock checks passed");
 }
}'''
        settings=lab.settings()
        with tempfile.TemporaryDirectory() as tmp:
            source=Path(tmp)/'WeatherCheck.java';source.write_text(java,encoding='utf-8')
            lab.command([lab.tool(settings,'javac'),'-encoding','UTF-8','-d',tmp,source,*[lab.ROOT/'app/src'/name for name in ('WeatherReading.java','LabWeatherUpdate.java','PhoneClock.java')],*list((lab.ROOT/'tests/stubs/org/json').glob('*.java'))])
            self.assertIn(b'weather and phone clock checks passed',lab.command([lab.tool(settings,'java'),'-cp',tmp,'dev.xr.rayneo.probe.WeatherCheck']).stdout)
