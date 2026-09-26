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
    /** backgroundService identifies a foreground-service flag or service category.
     * Reject matching notices in addition to the existing ongoing, summary and silent filters.
     * A visible "running" message alone does not establish which flags/category it carries. */
    static boolean eligible(boolean enabled,boolean selected,boolean ongoing,boolean groupSummary,boolean silent,boolean includeSilent,boolean backgroundService){
        return enabled&&selected&&!ongoing&&!groupSummary&&!backgroundService&&(!silent||includeSilent);
    }
    /** Foreground-service notification: the system flag, or the app's own "service" category. */
    static boolean backgroundService(int flags,String category){
        return (flags&FLAG_FOREGROUND_SERVICE)!=0||CATEGORY_SERVICE.equals(category);
    }
    /** android.app.Notification values, repeated so this class stays plain Java for the off-device check. */
    static final int FLAG_FOREGROUND_SERVICE=0x40;
    static final String CATEGORY_SERVICE="service";
}
