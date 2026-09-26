package dev.xr.rayneo.probe;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.companion.AssociationRequest;
import android.companion.BluetoothDeviceFilter;
import android.companion.CompanionDeviceManager;
import android.content.IntentSender;
import android.os.Build;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Requests the CompanionDeviceManager association that grants background execution and, from
 * API 31, device-presence wake-ups. The decision of whether to request at all lives in
 * CompanionAssociationPolicy so it can be checked offline; this class only talks to the framework. */
final class CompanionRegistration {
    static final int REQUEST_CODE = 71;

    /** Reads the MACs this app already holds. getMyAssociations() exists from API 33 and
     * getAssociations() is deprecated there, so both paths are kept: minSdk is 29. */
    static List<String> held(CompanionDeviceManager cdm) {
        List<String> macs = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            for (android.companion.AssociationInfo info : cdm.getMyAssociations()) {
                android.net.MacAddress mac = info.getDeviceMacAddress();
                if (mac != null) macs.add(mac.toString());
            }
        } else for (String mac : cdm.getAssociations()) macs.add(mac);
        return macs;
    }

    /** Returns null when nothing had to be done — either the association is already held or the
     * address is unusable — so the caller can report "already registered" instead of a failure. */
    static String request(Activity activity, String address) {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return "请先选择眼镜";
        CompanionDeviceManager cdm = activity.getSystemService(CompanionDeviceManager.class);
        if (cdm == null) return "系统不支持设备关联";
        if (!CompanionAssociationPolicy.needsRequest(held(cdm), address)) return null;
        // Match this one device by address: a name filter would offer a list and let the user pick
        // a different pair of glasses than the one configured here.
        AssociationRequest request = new AssociationRequest.Builder()
            .addDeviceFilter(new BluetoothDeviceFilter.Builder()
                .setAddress(address).build())
            .setSingleDevice(true).build();
        cdm.associate(request, new CompanionDeviceManager.Callback() {
            @Override public void onDeviceFound(IntentSender sender) {
                try { activity.startIntentSenderForResult(sender, REQUEST_CODE, null, 0, 0, 0); }
                catch (IntentSender.SendIntentException ignored) { }
            }
            @Override public void onFailure(CharSequence error) { }
        }, null);
        return "";
    }

    /** Turns on device-presence observation, which is what sets mNotifyOnDeviceNearby and makes the
     * system bind CompanionPresence when the glasses appear. The association alone leaves the flag
     * false: observed on our own association (id=34, false) against the official app (id=33, true).
     * Returns true when observation is now on. */
    static boolean observe(Activity activity, String address) {
        if (Build.VERSION.SDK_INT < 31) return false;
        CompanionDeviceManager cdm = activity.getSystemService(CompanionDeviceManager.class);
        if (cdm == null || !CompanionAssociationPolicy.holds(held(cdm), address)) return false;
        try { cdm.startObservingDevicePresence(address); return true; }
        catch (Exception denied) { return false; }
    }

    /** True once the association is actually recorded by the system — the callback returning is not
     * itself proof, the association only exists after the user confirms the system dialog. */
    static boolean holds(Activity activity, String address) {
        CompanionDeviceManager cdm = activity.getSystemService(CompanionDeviceManager.class);
        return cdm != null && CompanionAssociationPolicy.holds(held(cdm), address);
    }
}
