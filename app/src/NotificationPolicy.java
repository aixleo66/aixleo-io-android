package dev.xr.rayneo.probe;

/** Pure text boundaries shared by the notification listener and tests. */
final class NotificationPolicy {
    static long dispatchDelay(long now,long lastSent,long inFlightUntil){
        return Math.max(0,Math.max(lastSent<0?0:lastSent+250-now,inFlightUntil-now));
    }
    static String text(CharSequence input,int limit){
        if(input==null)return "";
        StringBuilder out=new StringBuilder();int count=0;
        for(int i=0;i<input.length()&&count<limit;){
            int cp=Character.codePointAt(input,i);i+=Character.charCount(cp);
            if(Character.isISOControl(cp)){if(cp=='\n'||cp=='\t')cp=' ';else continue;}
            out.appendCodePoint(cp);count++;
        }
        return out.toString().trim();
    }
    static boolean eligible(boolean enabled,boolean selected,boolean ongoing,boolean groupSummary,boolean silent,boolean includeSilent){
        return enabled&&selected&&!ongoing&&!groupSummary&&(!silent||includeSilent);
    }
}
