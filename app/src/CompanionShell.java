package dev.xr.rayneo.probe;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

/** Native navigation shell. Pages stay attached so switching never recreates a task. */
final class CompanionShell {
    static final int INK=0xffedf2fc, MUTED=0xffa6b4c9, GREEN=0xff79a7ff, BG=0xff101722, SURFACE=0xff192332, SOFT=0xff243859, BLUE_FILL=0xff315fc5;
    final Activity activity;
    final LinearLayout[] pages = new LinearLayout[13];
    final TextView connection, feedback;
    private final ScrollView[] scrolls = new ScrollView[13];
    private final String[] pageTitles=new String[13];
    private final LinearLayout header,nav;
    private final FrameLayout subnav;
    private final TextView subnavTitle;
    private final Button[] tabs = new Button[4];
    private final FrameLayout content;
    private final ShellNavigation navigation=new ShellNavigation();
    private final Button running;
    int selected=ShellNavigation.NOW;
    CompanionShell(Activity activity) {
        this.activity=activity;
        LinearLayout frame=new LinearLayout(activity); frame.setOrientation(1); frame.setBackgroundColor(BG);
        frame.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            v.setPadding(safe.left,safe.top,safe.right,safe.bottom); return insets;
        });
        header=new LinearLayout(activity); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(dp(22),dp(12),dp(22),dp(10));
        TextView brand=text("",14);brand.setTypeface(null,Typeface.BOLD);brand.setLetterSpacing(.09f);android.text.SpannableString wordmark=new android.text.SpannableString("AIX IO");wordmark.setSpan(new android.text.style.ForegroundColorSpan(GREEN),4,6,0);brand.setText(wordmark);header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        TextView version=text("开发预览",11); version.setTextColor(MUTED); header.addView(version); frame.addView(header);
        connection=text("正在读取连接状态…",13); connection.setTextColor(MUTED); connection.setPadding(dp(22),0,dp(22),dp(10)); frame.addView(connection);
        connection.setOnClickListener(v->show(ShellNavigation.DEVICE));
        subnav=new FrameLayout(activity);subnav.setPadding(dp(12),0,dp(12),0);
        subnavTitle=text("",20);subnavTitle.setGravity(Gravity.CENTER);subnavTitle.setSingleLine(true);subnavTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);subnavTitle.setPadding(dp(56),0,dp(56),0);subnav.addView(subnavTitle,new FrameLayout.LayoutParams(-1,dp(56)));
        ImageButton back=new ImageButton(activity);back.setImageDrawable(new ShellIcon(6,INK));back.setContentDescription("返回");back.setPadding(dp(13),dp(13),dp(13),dp(13));back.setBackground(shape(SURFACE,24));back.setOnClickListener(v->back());FrameLayout.LayoutParams backLayout=new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.START|Gravity.CENTER_VERTICAL);subnav.addView(back,backLayout);frame.addView(subnav,new LinearLayout.LayoutParams(-1,dp(64)));
        content=new FrameLayout(activity); frame.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        for(int i=0;i<pages.length;i++) {
            pages[i]=new LinearLayout(activity); pages[i].setOrientation(1); pages[i].setPadding(dp(22),dp(8),dp(22),dp(20));
            scrolls[i]=new ScrollView(activity); scrolls[i].setFillViewport(true); scrolls[i].addView(pages[i]); content.addView(scrolls[i]);
        }
        running=new Button(activity);running.setTextSize(13);running.setAllCaps(false);running.setTextColor(GREEN);running.setBackground(shape(SOFT,0));running.setOnClickListener(v->show(0));running.setVisibility(View.GONE);frame.addView(running);
        feedback=text("",12); feedback.setTextColor(MUTED); feedback.setPadding(dp(22),dp(6),dp(22),dp(6));
        feedback.setMaxLines(3); feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); frame.addView(feedback);
        nav=new LinearLayout(activity); nav.setPadding(dp(8),dp(7),dp(8),dp(7)); nav.setBackgroundColor(SURFACE);
        String[] titles={"此刻","功能","记录","设置"};
        for(int i=0;i<4;i++) {final int page=ShellNavigation.MAIN[i]; tabs[i]=new Button(activity); tabs[i].setText(titles[i]); tabs[i].setTextSize(12);tabs[i].setPadding(0,dp(8),0,dp(5)); tabs[i].setAllCaps(false); tabs[i].setMinHeight(dp(58)); tabs[i].setOnClickListener(v->selectMain(page)); nav.addView(tabs[i],new LinearLayout.LayoutParams(0,-2,1));}
        frame.addView(nav); activity.setContentView(frame);
        if(activity.getWindow().getInsetsController()!=null)activity.getWindow().getInsetsController().setSystemBarsAppearance(
            0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        for(Button tab:tabs){tab.setStateListAnimator(null);tab.setElevation(0);}
        activity.getWindow().setStatusBarColor(BG);activity.getWindow().setNavigationBarColor(SURFACE);
        frame.requestApplyInsets(); selectMain(ShellNavigation.NOW);
    }
    int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    TextView text(String value,int size){TextView t=new TextView(activity);t.setText(value);t.setTextSize(size);t.setTextColor(INK);return t;}
    GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    void show(int page){navigation.open(page);renderRoute();}
    void selectMain(int page){navigation.selectMain(page);renderRoute();}
    boolean back(){boolean moved=navigation.back();renderRoute();return moved;}
    private void renderRoute(){selected=navigation.selected;boolean main=navigation.isMain(selected);header.setVisibility(main?View.VISIBLE:View.GONE);connection.setVisibility(main?View.VISIBLE:View.GONE);nav.setVisibility(main?View.VISIBLE:View.GONE);subnav.setVisibility(main?View.GONE:View.VISIBLE);subnavTitle.setText(pageTitles[selected]);for(int i=0;i<pages.length;i++)scrolls[i].setVisibility(i==selected?View.VISIBLE:View.GONE);for(int i=0;i<4;i++){boolean on=navigation.main==ShellNavigation.MAIN[i];tabs[i].setTextColor(on?GREEN:MUTED);tabs[i].setBackground(shape(on?SOFT:SURFACE,14));tabs[i].setSelected(on);ShellIcon icon=new ShellIcon(i,on?GREEN:MUTED);icon.setBounds(0,0,dp(20),dp(20));tabs[i].setCompoundDrawables(null,icon,null,null);tabs[i].setCompoundDrawablePadding(dp(4));}}
    void title(int page,String title,String subtitle){pageTitles[page]=title;if(navigation.isMain(page)){TextView t=text(title,28);t.setTypeface(null,Typeface.BOLD);t.setPadding(0,dp(12),0,dp(6));pages[page].addView(t);}note(page,subtitle);}
    void note(int page,String value){TextView t=text(value,14);t.setTextColor(MUTED);t.setPadding(0,dp(4),0,dp(18));pages[page].addView(t);}
    void section(int page,String value){TextView t=text(value,18);t.setTypeface(null,Typeface.BOLD);t.setPadding(0,dp(20),0,dp(10));pages[page].addView(t);}
    TextView card(int page,String value){TextView t=text(value,16);t.setLineSpacing(dp(3),1);t.setPadding(dp(20),dp(22),dp(20),dp(22));t.setBackground(shape(SURFACE,20));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(6),0,dp(12));pages[page].addView(t,p);return t;}
    void heroText(TextView t,String value){android.text.SpannableString s=new android.text.SpannableString(value);int end=value.indexOf('\n');if(end<0)end=value.length();s.setSpan(new android.text.style.RelativeSizeSpan(23f/16f),0,end,0);t.setText(s);}
    TextView hero(int page,String value){TextView t=card(page,value);t.setTextSize(16);t.setTextColor(Color.WHITE);heroText(t,value);GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff18365f,0xff284a87});d.setCornerRadius(dp(22));t.setBackground(d);return t;}
    Button action(int page,String label,boolean primary,Runnable run){Button b=new Button(activity);b.setText(label);b.setAllCaps(false);b.setTextSize(15);b.setPadding(dp(16),dp(12),dp(16),dp(12));b.setMinHeight(dp(50));b.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{MUTED,primary?Color.WHITE:GREEN}));b.setBackgroundTintList(null);b.setBackground(shape(primary?BLUE_FILL:SOFT,14));b.setStateListAnimator(null);b.setElevation(0);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(5),0,dp(5));pages[page].addView(b,p);b.setOnClickListener(v->run.run());return b;}
    TextView row(int page,String title,String subtitle,int icon,Runnable run){TextView t=card(page,"");t.setTextSize(16);android.text.SpannableString label=new android.text.SpannableString(title+"\n"+subtitle);label.setSpan(new android.text.style.ForegroundColorSpan(MUTED),title.length()+1,label.length(),0);label.setSpan(new android.text.style.AbsoluteSizeSpan(13,true),title.length()+1,label.length(),0);t.setText(label);ShellIcon d=new ShellIcon(icon,GREEN);d.setBounds(0,0,dp(25),dp(25));t.setCompoundDrawables(d,null,null,null);t.setCompoundDrawablePadding(dp(16));t.setMinHeight(dp(80));t.setGravity(Gravity.CENTER_VERTICAL);t.setClickable(true);t.setFocusable(true);t.setOnClickListener(v->run.run());return t;}
    void task(String label,boolean active){running.setText(label+" · 返回录音 ›");running.setVisibility(active?View.VISIBLE:View.GONE);}
    void unavailable(int page,String label){Button b=action(page,label,false,()->{});b.setEnabled(false);}
}
