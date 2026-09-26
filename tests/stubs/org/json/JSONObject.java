package org.json;
import java.util.*;
import java.util.regex.*;
/** Minimal fixture object: parses flat recorder events only, not a JSON conformance test. */
public class JSONObject {
    public static final Object NULL=new Object();
    private final Map<String,Object> fields=new LinkedHashMap<>();
    public JSONObject(){}
    public JSONObject(String input){
        Matcher m=Pattern.compile("\"([^\"]+)\"\\s*:\\s*(\"[^\"]*\"|true|false|-?[0-9]+)").matcher(input);
        while(m.find()){String v=m.group(2);put(m.group(1),v.startsWith("\"")?v.substring(1,v.length()-1):v.equals("true")?true:v.equals("false")?false:Long.valueOf(v));}
    }
    public JSONObject put(String k,Object v){fields.put(k,v);return this;}
    public boolean has(String k){return fields.containsKey(k);}
    public int length(){return fields.size();}
    public Object opt(String k){return fields.get(k);}
    public Object remove(String k){return fields.remove(k);}
    public Object get(String k){if(!has(k))throw new IllegalArgumentException(k);return fields.get(k);}
    public Iterator<String> keys(){return fields.keySet().iterator();}
    public String optString(String k){return optString(k,"");}
    public String optString(String k,String fallback){Object v=fields.get(k);return v==null?fallback:String.valueOf(v);}
    public String getString(String k){if(!has(k))throw new IllegalArgumentException(k);return optString(k);}
    public int getInt(String k){if(!has(k))throw new IllegalArgumentException(k);
        Object v=fields.get(k);if(!(v instanceof Number))throw new IllegalArgumentException(k);
        return ((Number)v).intValue();}
    /** Matches org.json: true when the key is absent or holds the NULL sentinel. */
    public boolean isNull(String k){Object v=fields.get(k);return v==null||v==NULL;}
    public int optInt(String k){return optInt(k,0);}
    public int optInt(String k,int fallback){Object v=fields.get(k);return v instanceof Number?((Number)v).intValue():fallback;}
    public long optLong(String k){Object v=fields.get(k);return v instanceof Number?((Number)v).longValue():0;}
    public long optLong(String k,long fallback){Object v=fields.get(k);return v instanceof Number?((Number)v).longValue():fallback;}
    public long getLong(String k){if(!has(k))throw new IllegalArgumentException(k);return optLong(k);}
    public boolean optBoolean(String k){return Boolean.TRUE.equals(fields.get(k));}
    public JSONObject optJSONObject(String k){Object v=fields.get(k);return v instanceof JSONObject?(JSONObject)v:null;}
    public JSONObject getJSONObject(String k){JSONObject v=optJSONObject(k);if(v==null)throw new IllegalArgumentException(k);return v;}
    public JSONArray optJSONArray(String k){Object v=fields.get(k);return v instanceof JSONArray?(JSONArray)v:null;}
    public JSONArray getJSONArray(String k){JSONArray v=optJSONArray(k);if(v==null)throw new IllegalArgumentException(k);return v;}
    static String render(Object value){
        if(!(value instanceof String))return String.valueOf(value);
        String text=(String)value;
        return "\""+text.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+"\"";
    }
    public String toString(){StringJoiner out=new StringJoiner(",","{","}");for(Map.Entry<String,Object> e:fields.entrySet())out.add(render(e.getKey())+":"+render(e.getValue()));return out.toString();}
    public String toString(int indent){return toString();}
}
