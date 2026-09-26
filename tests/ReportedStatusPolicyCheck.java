package dev.xr.rayneo.probe;

final class ReportedStatusPolicyCheck {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        check(ReportedStatusPolicy.include("micStatus", 0));
        check(ReportedStatusPolicy.include("focusMode", false));
        check(ReportedStatusPolicy.include("batt_temp", -1.25));
        check(ReportedStatusPolicy.include("screenStatus", 12345)); // Unknown enum stays raw.
        for (String privateKey : new String[]{"deviceName", "bluetoothAddress", "prescription", "firmware", "unknown"})
            check(!ReportedStatusPolicy.include(privateKey, 1));
        for (Object wrongType : new Object[]{null, "0", new Object(), Double.NaN, Double.POSITIVE_INFINITY})
            check(!ReportedStatusPolicy.include("battery", wrongType));
        check(ReportedStatusPolicy.includeFocus("enable", 0));
        check(ReportedStatusPolicy.includeFocus("enableGlassClose", false));
        check(ReportedStatusPolicy.includeFocus("policy.type", 999));
        for(String key:new String[]{"policy.token","deviceName","policy.extra","enable.extra"})
            check(!ReportedStatusPolicy.includeFocus(key,1));
        for(Object value:new Object[]{null,"0","22:00",new int[]{1,2},Double.NaN,Double.POSITIVE_INFINITY})
            check(!ReportedStatusPolicy.includeFocus("policy.begin",value));
        System.out.println("status scalar filtering, zero/false preservation and private fields checks passed");
    }
}
