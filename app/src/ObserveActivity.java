package dev.xr.rayneo.probe;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.widget.TextView;
import java.io.*;
import java.util.*;
import org.json.*;

/** Public Android observation only: no GATT connection, writes, pairing or vendor calls. */
public final class ObserveActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final JSONObject result = new JSONObject();
    private final JSONArray stages = new JSONArray();
    private BluetoothLeScanner scanner;
    private BluetoothManager manager;
    private String target;
    private TextView display;
    private boolean scanning, finished;
    private int hits;
    private long scanStarted;

    private void persist() throws Exception {
        result.put("stages", stages);
        File tmp = new File(getFilesDir(), "result.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(result.toString(2).getBytes("UTF-8"));
            out.getFD().sync();
        }
        if (!tmp.renameTo(new File(getFilesDir(), "result.json"))) throw new IOException("result rename");
    }

    private void stage(String name, Object value) throws Exception {
        stages.put(new JSONObject().put("stage", name).put("value", value));
        persist();
    }

    private boolean systemConnected() {
        for (BluetoothDevice device : manager.getConnectedDevices(BluetoothProfile.GATT)) {
            if (target.equalsIgnoreCase(device.getAddress())) return true;
        }
        return false;
    }

    private final ScanCallback callback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult value) { accept(value); }
        @Override public void onBatchScanResults(List<ScanResult> values) {
            for (ScanResult value : values) accept(value);
        }
        @Override public void onScanFailed(int error) {
            complete("failed", "BLE scan error " + error);
        }
    };

    private void accept(ScanResult value) {
        if (finished) return;
        try {
            if (!target.equalsIgnoreCase(value.getDevice().getAddress())) return;
            hits++;
            result.put("target_scan_hits", hits).put("last_rssi", value.getRssi());
            if (hits == 1) {
                JSONObject seen = new JSONObject().put("address", target)
                    .put("rssi", value.getRssi()).put("connectable", value.isConnectable());
                ScanRecord record = value.getScanRecord();
                if (record != null) {
                    seen.put("advertised_name", record.getDeviceName());
                    JSONArray uuids = new JSONArray();
                    if (record.getServiceUuids() != null)
                        for (ParcelUuid uuid : record.getServiceUuids()) uuids.put(uuid.toString());
                    seen.put("service_uuids", uuids);
                }
                stage("first_target_advertisement", seen);
            }
        } catch (Exception e) { complete("failed", e.toString()); }
    }

    private void complete(String status, String error) {
        if (finished) return;
        finished = true;
        handler.removeCallbacksAndMessages(null);
        try {
            if (scanning) {
                scanner.stopScan(callback);
                scanning = false;
                result.put("scan_stopped", true);
            }
            if (scanStarted != 0) {
                result.put("scan_elapsed_ms", SystemClock.elapsedRealtime() - scanStarted);
                result.put("system_gatt_connected_at_end", systemConnected());
            }
        } catch (Exception e) { status = "failed"; error = "observation cleanup: " + e; }
        try {
            result.put("status", status).put("target_scan_hits", hits)
                .put("advertisement_observed", hits > 0);
            if (error != null) result.put("error", error);
            stage("observation_finished", true);
            display.setText(result.toString(2));
        } catch (Exception e) { android.util.Log.e("RayNeoObserve", "persist final result", e); }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        display = new TextView(this);
        display.setPadding(24, 48, 24, 24);
        display.setText("雷鸟设备观察\n读取系统连接状态，扫描指定眼镜 12 秒。\n保持现有连接与配对。");
        setContentView(display);
        try {
            result.put("status", "running").put("package", getPackageName())
                .put("pid", android.os.Process.myPid()).put("mode", "observe")
                .put("sdk_int", Build.VERSION.SDK_INT)
                .put("independent_connection_attempted", false).put("vendor_sdk_called", false)
                .put("scan_never_for_location", true);
            stage("begin", true);
            if (Build.VERSION.SDK_INT < 31) throw new IllegalStateException("Observation requires Android 12+");
            target = getIntent().getStringExtra("target_address");
            if (!BluetoothAdapter.checkBluetoothAddress(target)) throw new IllegalArgumentException("Explicit target address required");
            for (String permission : new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN})
                if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
                    throw new SecurityException("Missing permission: " + permission);
            manager = getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) throw new IllegalStateException("Bluetooth is not enabled");
            BluetoothDevice device = adapter.getRemoteDevice(target);
            stage("target", new JSONObject().put("address", target).put("name", device.getName())
                .put("bond_state", device.getBondState()));
            result.put("system_gatt_connected_at_start", systemConnected());
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) throw new IllegalStateException("BLE scanner unavailable");
            scanStarted = SystemClock.elapsedRealtime();
            scanning = true;
            scanner.startScan(Collections.singletonList(new ScanFilter.Builder().setDeviceAddress(target).build()),
                new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback);
            stage("target_scan_started", true);
            handler.postDelayed(() -> complete("observation_completed", null), 12000);
        } catch (Exception e) { complete("failed", e.toString()); }
    }

    @Override protected void onPause() {
        if (!finished) complete("failed", "Observation interrupted before completion");
        super.onPause();
    }
}
