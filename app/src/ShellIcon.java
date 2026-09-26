package dev.xr.rayneo.probe;
import android.graphics.*;
import android.graphics.drawable.Drawable;
/** Original line icons using one 24-unit grid and consistent stroke weights. */
final class ShellIcon extends Drawable {
    private final int kind,color;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    ShellIcon(int kind,int color){this.kind=kind;this.color=color;}
    public void draw(Canvas c){c.save();Rect b=getBounds();c.translate(b.left,b.top);c.scale(b.width()/24f,b.height()/24f);paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.65f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        if(kind==0){c.drawCircle(12,12,8,paint);paint.setStyle(Paint.Style.FILL);c.drawCircle(12,12,2,paint);}
        else if(kind==1){for(int x:new int[]{4,14})for(int y:new int[]{4,14})c.drawRoundRect(x,y,x+6,y+6,1.5f,1.5f,paint);}
        else if(kind==2){c.drawRoundRect(6,3,20,19,2,2,paint);c.drawLine(3,7,3,22,paint);c.drawLine(3,22,16,22,paint);c.drawLine(10,8,16,8,paint);c.drawLine(10,12,16,12,paint);}
        else if(kind==4){c.drawRoundRect(9,3,15,14,3,3,paint);c.drawArc(6,7,18,18,0,180,false,paint);c.drawLine(12,18,12,22,paint);c.drawLine(8,22,16,22,paint);}
        else if(kind==5){c.drawRoundRect(3,4,21,18,4,4,paint);c.drawLine(7,18,5,22,paint);c.drawLine(5,22,12,18,paint);}
        else if(kind==6){c.drawLine(15,4,7,12,paint);c.drawLine(7,12,15,20,paint);}
        else{for(int y:new int[]{6,12,18})c.drawLine(3,y,21,y,paint);paint.setStyle(Paint.Style.FILL);c.drawCircle(8,6,2.5f,paint);c.drawCircle(16,12,2.5f,paint);c.drawCircle(10,18,2.5f,paint);}c.restore();}
    public void setAlpha(int a){paint.setAlpha(a);}public void setColorFilter(ColorFilter f){paint.setColorFilter(f);}public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
