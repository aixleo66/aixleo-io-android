package dev.xr.rayneo.probe;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

/** Native navigation shell. Pages stay attached so switching never recreates a task. */
final class CompanionShell {
    static final int INK=0xff203d32, MUTED=0xff5e7068, GREEN=0xff286647, BG=0xfff3f6f2;
    final Activity activity;
    final LinearLayout[] pages = new LinearLayout[7];
    final TextView connection, feedback;
    private final ScrollView[] scrolls = new ScrollView[7];
    private final Button[] tabs = new Button[4];
    private final FrameLayout content;
    int selected;
    CompanionShell(Activity activity) {
        this.activity=activity;
        LinearLayout frame=new LinearLayout(activity); frame.setOrientation(1); frame.setBackgroundColor(BG);
        frame.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(safe.left,safe.top,safe.right,safe.bottom); return insets;
        });
        LinearLayout header=new LinearLayout(activity); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(dp(22),dp(12),dp(22),dp(10));
        TextView brand=text("Aixleo iO",20); brand.setTypeface(null,Typeface.BOLD); header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        TextView version=text("预览版",12); version.setTextColor(MUTED); header.addView(version); frame.addView(header);
        connection=text("正在读取连接状态…",13); connection.setTextColor(MUTED); connection.setPadding(dp(22),0,dp(22),dp(10)); frame.addView(connection);
        content=new FrameLayout(activity); frame.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        for(int i=0;i<pages.length;i++) {
            pages[i]=new LinearLayout(activity); pages[i].setOrientation(1); pages[i].setPadding(dp(22),dp(8),dp(22),dp(20));
            scrolls[i]=new ScrollView(activity); scrolls[i].setFillViewport(true); scrolls[i].addView(pages[i]); content.addView(scrolls[i]);
        }
        feedback=text("",13); feedback.setTextColor(MUTED); feedback.setPadding(dp(22),dp(6),dp(22),dp(6));
        feedback.setMaxLines(3); feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); frame.addView(feedback);
        LinearLayout nav=new LinearLayout(activity); nav.setPadding(dp(8),dp(6),dp(8),dp(6)); nav.setBackgroundColor(Color.WHITE);
        String[] titles={"录音","助手","消息","设备"};
        for(int i=0;i<4;i++) {final int page=i; tabs[i]=new Button(activity); tabs[i].setText(titles[i]); tabs[i].setTextSize(15); tabs[i].setAllCaps(false); tabs[i].setMinHeight(dp(52)); tabs[i].setOnClickListener(v->show(page)); nav.addView(tabs[i],new LinearLayout.LayoutParams(0,-2,1));}
        frame.addView(nav); activity.setContentView(frame);
        if(activity.getWindow().getInsetsController()!=null)activity.getWindow().getInsetsController().setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        for(Button tab:tabs){tab.setStateListAnimator(null);tab.setElevation(0);}
        frame.requestApplyInsets(); show(0);
    }
    int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    TextView text(String value,int size){TextView t=new TextView(activity);t.setText(value);t.setTextSize(size);t.setTextColor(INK);return t;}
    GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    void show(int page){selected=page;for(int i=0;i<pages.length;i++)scrolls[i].setVisibility(i==page?View.VISIBLE:View.GONE);int main=page>=4?3:page;for(int i=0;i<4;i++){tabs[i].setTextColor(i==main?GREEN:MUTED);tabs[i].setBackground(shape(i==main?0xffe1eee2:Color.WHITE,14));tabs[i].setSelected(i==main);}}
    void title(int page,String title,String subtitle){TextView t=text(title,28);t.setTypeface(null,Typeface.BOLD);t.setPadding(0,dp(12),0,dp(6));pages[page].addView(t);note(page,subtitle);}
    void note(int page,String value){TextView t=text(value,14);t.setTextColor(MUTED);t.setPadding(0,dp(4),0,dp(18));pages[page].addView(t);}
    void section(int page,String value){TextView t=text(value,18);t.setTypeface(null,Typeface.BOLD);t.setPadding(0,dp(20),0,dp(10));pages[page].addView(t);}
    TextView card(int page,String value){TextView t=text(value,17);t.setPadding(dp(20),dp(22),dp(20),dp(22));t.setBackground(shape(Color.WHITE,18));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(6),0,dp(12));pages[page].addView(t,p);return t;}
    Button action(int page,String label,boolean primary,Runnable run){Button b=new Button(activity);b.setText(label);b.setAllCaps(false);b.setTextSize(16);b.setPadding(dp(14),dp(12),dp(14),dp(12));b.setMinHeight(dp(52));b.setTextColor(primary?Color.WHITE:GREEN);b.setBackground(shape(primary?GREEN:0xffe3eee4,14));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(5),0,dp(5));pages[page].addView(b,p);b.setOnClickListener(v->run.run());return b;}
    void unavailable(int page,String label){Button b=action(page,label,false,()->{});b.setEnabled(false);b.setTextColor(MUTED);b.setBackground(shape(0xffe4e8e3,14));}
}
