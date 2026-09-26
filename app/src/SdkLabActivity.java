package dev.xr.rayneo.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Separate-package entry. Opening this page never initializes Bluetooth or starts a service. */
public final class SdkLabActivity extends Activity {
    private TextView status;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!"dev.xr.rayneo.sdklab".equals(getPackageName())) { finish(); return; }
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(28, 28, 28, 28);
        ScrollView scroll = new ScrollView(this); scroll.addView(column); setContentView(scroll);
        TextView title = new TextView(this);
        String installedVersion = "版本未知";
        try { installedVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
        title.setText("AIX IO SDK Lab\n" + installedVersion + "\n独立调试版，数据与日常版分开保存。");
        title.setTextSize(21); column.addView(title);
        TextView hint = new TextView(this);
        hint.setText("本页不会自动连接眼镜。先验证 SDK 初始化，再在官方 App 与日常版均已断开后进行连接和录音测试。初始化通过不等于设备能力通过。");
        column.addView(hint);
        add(column, "验证 SDK 初始化（不连接眼镜）", () ->
            startActivity(new Intent(this, ProbeActivity.class).putExtra("autorun", true)));
        add(column, "进入连接与录音验证", () -> new AlertDialog.Builder(this)
            .setTitle("确认眼镜可供调试")
            .setMessage("请先在官方 App 或日常版内主动断开眼镜，不要同时运行两个连接客户端。若之前连接过且未点「断开眼镜」，设备页打开后会自动连接。")
            .setNegativeButton("返回", null)
            .setPositiveButton("已断开，打开设备页", (dialog, which) -> {
                // No longer clears auto_connect: that silently switched off automatic reconnect for a
                // user who only opened the device page (09-23 17:04 on device). "断开眼镜" still clears it.
                startActivity(new Intent(this, CloudActivity.class));
            }).show());
        add(column, "刷新本次报告", this::refresh);
        add(column, "验证已保存录音解码（不录音）", () -> SdkLabDecodeBenchmark.select(this, text -> status.setText(text)));
        add(column, "导出本次报告", () -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .setType("application/json").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_TITLE, "io-sdk-lab-result.json"), 81));
        status = new TextView(this); status.setTextSize(13); column.addView(status); refresh();
    }
    private void add(LinearLayout column, String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setOnClickListener(v -> action.run()); column.addView(b);
    }
    private File resultFile() { return new File(getFilesDir(), "result.json"); }
    private void refresh() {
        try {
            File f = resultFile();
            if (!f.isFile()) { status.setText("尚无本次报告。"); return; }
            try (FileInputStream in = new FileInputStream(f)) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[8192]; int n;
                while ((n = in.read(b)) != -1) out.write(b, 0, n);
                status.setText(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (Exception e) { status.setText("读取失败：" + e.getMessage()); }
    }
    @Override protected void onResume() { super.onResume(); if (status != null) refresh(); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 81 || result != RESULT_OK || data == null || data.getData() == null) return;
        try (FileInputStream in = new FileInputStream(resultFile());
             OutputStream out = getContentResolver().openOutputStream(data.getData())) {
            if (out == null) throw new java.io.IOException("无法打开导出位置");
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
            status.setText("报告已导出。报告可能包含设备标识或调试内容，请仅保存在自己的资料目录。");
        } catch (Exception e) { status.setText("导出失败：" + e.getMessage()); }
    }
}
