package org.json;
import java.util.*;
public class JSONArray {
    private final List<Object> values=new ArrayList<>();
    public JSONArray put(Object value){values.add(value);return this;}
    public int length(){return values.size();}
    public JSONObject optJSONObject(int i){Object v=values.get(i);return v instanceof JSONObject?(JSONObject)v:null;}
    public JSONObject getJSONObject(int i){JSONObject v=optJSONObject(i);if(v==null)throw new IllegalArgumentException();return v;}
}
