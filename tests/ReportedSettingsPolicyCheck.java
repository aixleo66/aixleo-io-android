package dev.xr.rayneo.probe;
public class ReportedSettingsPolicyCheck{
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  check(ReportedSettingsPolicy.include("generalSettings.displayConfig.height",0));
  check(ReportedSettingsPolicy.include("generalSettings.privacyConfig.led_auto_light",false));
  check(ReportedSettingsPolicy.include("generalSettings.crownConfig.longPress",2));
  for(String k:new String[]{"generalSettings.sn","generalSettings.firmwareVersion","generalSettings.osVersion","generalSettings.deviceName","other.crownConfig.longPress","longPress","generalSettings.unexpected.mode"})check(!ReportedSettingsPolicy.include(k,7));
  for(Object v:new Object[]{null,"7",Double.NaN,Double.POSITIVE_INFINITY})check(!ReportedSettingsPolicy.include("generalSettings.storageUsed",v));
  check(!ReportedSettingsPolicy.include("generalSettings.privacyConfig.led_light","private-text"));System.out.println("settings policy checks passed");
 }
}
