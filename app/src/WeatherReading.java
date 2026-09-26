package dev.xr.rayneo.probe;

import org.json.JSONObject;

/** Open-Meteo WMO categories -> representative official iO weather icon categories. */
final class WeatherReading {
    final JSONObject source;
    final int temperature,icon;
    final String description;
    private WeatherReading(JSONObject source,int temperature,int icon,String description){this.source=source;this.temperature=temperature;this.icon=icon;this.description=description;}
    static WeatherReading parse(JSONObject source,long now)throws Exception{
        if(!"Open-Meteo".equals(source.optString("source"))||!"android_approximate_location".equals(source.optString("location_source")))throw new IllegalArgumentException("天气来源不匹配");
        long fetched=integer(source,"fetched_at_ms");
        if(fetched>now+60000||now-fetched>600000)throw new IllegalArgumentException("天气数据已过期，请重新刷新");
        JSONObject response=source.getJSONObject("response"),current=response.getJSONObject("current"),units=response.getJSONObject("current_units");
        if(!"°C".equals(units.optString("temperature_2m"))||!"unixtime".equals(units.optString("time")))throw new IllegalArgumentException("天气单位无法确认");
        long measured=integer(current,"time");
        if(measured>now/1000+300||now/1000-measured>7200)throw new IllegalArgumentException("天气时效无法确认");
        double temperature=number(current,"temperature_2m");
        if(temperature< -100||temperature>70)throw new IllegalArgumentException("天气温度异常");
        long rawCode=integer(current,"weather_code"),rawDay=integer(current,"is_day");
        if(rawCode<0||rawCode>99||rawDay<0||rawDay>1)throw new IllegalArgumentException("天气类型或昼夜标志无效");
        int code=(int)rawCode,day=(int)rawDay;
        if(day!=0&&day!=1)throw new IllegalArgumentException("天气昼夜标志无效");
        int icon;String description;
        switch(code){
            case 0:icon=day==1?100:150;description="晴";break;
            case 1:case 2:icon=day==1?101:151;description="多云";break;
            case 3:icon=day==1?101:151;description="阴";break;
            case 45:case 48:icon=500;description="雾";break;
            case 51:case 53:case 55:case 56:case 57:case 61:case 63:case 65:case 66:case 67:case 80:case 81:case 82:icon=305;description="雨";break;
            case 71:case 73:case 75:case 77:case 85:case 86:icon=400;description="雪";break;
            case 95:case 96:case 99:icon=302;description="雷雨";break;
            default:throw new IllegalArgumentException("暂不支持这一天气类型，未更新眼镜");
        }
        return new WeatherReading(source,(int)Math.round(temperature),icon,description);
    }
    static double number(JSONObject value,String key)throws Exception{
        Object raw=value.get(key);if(!(raw instanceof Number))throw new IllegalArgumentException("天气字段缺失或格式异常："+key);
        double number=((Number)raw).doubleValue();if(!Double.isFinite(number))throw new IllegalArgumentException("天气数值无效");return number;
    }
    static long integer(JSONObject value,String key)throws Exception{
        double number=number(value,key);if(number!=Math.rint(number)||Math.abs(number)>9007199254740991d)throw new IllegalArgumentException("天气整数字段异常");return (long)number;
    }
    JSONObject payload(long now)throws Exception{
        // Revalidate freshness at send time; source time is not the protocol send timestamp.
        parse(source,now);
        JSONObject data=new JSONObject().put("location","当前位置").put("icon",icon).put("temp",temperature);
        return new JSONObject().put("cmd","current_weather_update").put("payload",new JSONObject()
            .put("value",0).put("mode",0).put("data",data.toString()).put("ts",Long.toString(now/1000)));
    }
}
