package dev.xr.rayneo.probe;
final class ConnectionDiagnosticCheck {
    public static void main(String[] args) {
        String result=VendorConnectionDiagnostics.classify("BLEConnectManager",
            "Notification descriptor setup failed device=private-address, reason=descriptor not found, characteristic=private-id");
        if(!"descriptor_not_found".equals(result))throw new AssertionError();
        if(!VendorConnectionDiagnostics.classify("RNAuthManager","descriptor not found secret").isEmpty())throw new AssertionError();
        if(!VendorConnectionDiagnostics.classify("BLEConnectManager","raw unknown failure secret").isEmpty())throw new AssertionError();
        if(!VendorConnectionDiagnostics.classify("BLEConnectManager",null).isEmpty())throw new AssertionError();
        if(!"services_discovered".equals(VendorConnectionDiagnostics.phase("BLEConnectManager","Services discovered for device: private-address")))throw new AssertionError();
        if(!VendorConnectionDiagnostics.phase("RNAuthManager","Services discovered for device: secret").isEmpty())throw new AssertionError();
        if(!VendorConnectionDiagnostics.phase("BLEConnectManager","unrecognized private message").isEmpty())throw new AssertionError();
        System.out.println("diagnostic redaction checks passed");
    }
}
