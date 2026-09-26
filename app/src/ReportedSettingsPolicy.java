package dev.xr.rayneo.probe;
import java.util.Arrays;
/** Exact observed paths: values elsewhere and all identity/string fields stay shape-only. */
final class ReportedSettingsPolicy {
 static boolean include(String path,Object value){
  if(!Arrays.asList(
   "generalSettings.displayConfig.height","generalSettings.displayConfig.distance",
   "generalSettings.headGestures.enabled","generalSettings.headGestures.mode",
   "generalSettings.crownConfig.direction","generalSettings.crownConfig.double","generalSettings.crownConfig.longPress",
   "generalSettings.privacyConfig.mic_switch","generalSettings.privacyConfig.led_light",
   "generalSettings.privacyConfig.led_auto_light","generalSettings.privacyConfig.log_switch",
   "generalSettings.wakeupConfig.headupSwitch","generalSettings.wakeupConfig.headupDegree","generalSettings.wakeupConfig.crownSwitch",
   "generalSettings.autoLockTime","generalSettings.storageTotal","generalSettings.storageUsed"
  ).contains(path))return false;
  if(value instanceof Boolean)return true;
  return value instanceof Number&&Double.isFinite(((Number)value).doubleValue());
 }
}
