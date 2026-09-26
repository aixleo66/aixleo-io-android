package dev.xr.rayneo.probe;

/** Whitelists connection reasons; never retains raw SDK log text. */
final class VendorConnectionDiagnostics {
    static String classify(String tag, String message) {
        if (!"BLEConnectManager".equals(tag) || message == null) return "";
        for(String reason : new String[]{"core device not found", "receive characteristic not found",
                "enable characteristic notification failed", "descriptor not found",
                "descriptor write did not start", "descriptor write threw exception",
                "notification setup threw exception", "descriptor write failed",
                "Service discovery failed", "Error populating device chrtUUID"})
            if(message.contains(reason))return reason.replace(' ','_');
        return "";
    }
    static String phase(String tag, String message) {
        if (!"BLEConnectManager".equals(tag) || message == null) return "";
        String[][] phases = {
            {"Device connected:","gatt_connected"},
            {"discoverServices()","discovery_requested"},
            {"Services discovered for device:","services_discovered"},
            {"Device UUID type determined:","characteristics_loaded"},
            {"Notification descriptor write requested", "notification_write_requested"},
            {"BLE fully ready", "notifications_ready"},
            {"Request disconnect for device:","sdk_disconnect_requested"},
            {"Device disconnected:","gatt_disconnected"}
        };
        for (String[] phase : phases) if(message.startsWith(phase[0])) return phase[1];
        return "";
    }
}
