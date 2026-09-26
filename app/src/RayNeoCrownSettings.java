package dev.xr.rayneo.probe;

import org.json.JSONObject;

/** Fixed RayNeo 1.0.4 wire contract; device compatibility is verified separately. */
final class RayNeoCrownSettings {
    enum Direction { STANDARD(0), NATURAL(1); final int wire; Direction(int wire){this.wire=wire;} }
    final int direction, doubleTap, screenOffLongPress;
    private RayNeoCrownSettings(int direction,int doubleTap,int longPress){
        this.direction=direction;this.doubleTap=doubleTap;this.screenOffLongPress=longPress;
    }
    private static int integer(Object value){
        if(!(value instanceof Number))return -1;
        double n=((Number)value).doubleValue();
        return n>=0&&n<=9&&n==(int)n?(int)n:-1;
    }
    static RayNeoCrownSettings read(JSONObject values,String prefix){
        if(values==null)return null;
        int direction=integer(values.opt(prefix+"direction"));
        int twice=integer(values.opt(prefix+"double"));
        int hold=integer(values.opt(prefix+"longPress"));
        if(direction<0||direction>1||twice<0||hold<0||hold==6)return null;
        return new RayNeoCrownSettings(direction,twice,hold);
    }
    RayNeoCrownSettings withDirection(Direction direction){
        return new RayNeoCrownSettings(direction.wire,doubleTap,screenOffLongPress);
    }
    RayNeoCrownSettings alternateDirection(){return withDirection(direction==1?Direction.STANDARD:Direction.NATURAL);}
    JSONObject snapshot()throws Exception{
        return new JSONObject().put("direction",direction).put("double",doubleTap).put("longPress",screenOffLongPress);
    }
    JSONObject payload()throws Exception{
        // data is an encoded JSON string. It must not be replaced with a nested object.
        String data=new JSONObject().put("double",doubleTap).put("longPress",screenOffLongPress).toString();
        return new JSONObject().put("cmd","crown_config").put("payload",new JSONObject()
            .put("value",direction).put("mode",0).put("data",data));
    }
    boolean same(RayNeoCrownSettings other){return other!=null&&direction==other.direction
        &&doubleTap==other.doubleTap&&screenOffLongPress==other.screenOffLongPress;}
}
