package dev.xr.rayneo.probe;

import org.json.JSONObject;

/** Turns one requested setting change into the business packet for it, or refuses.
 *
 * <p>Separate from the lab trials on purpose. A trial writes a value, reads it back and restores
 * the original a few seconds later -- correct for an experiment, useless for a product, where the
 * point is that the value stays. This class only produces the write.
 *
 * <p>Every range here comes from the observed command shapes and the official settings page, not from
 * guesses. Anything outside them is refused rather than clamped: silently writing a different
 * value than the user chose is worse than telling them it was rejected.
 *
 * <p>The type/cmd/payload shapes are not uniform -- {@code auto_lock_time}
 * sends explicit nulls, {@code crown_config} puts JSON in {@code data} as a *string*, and
 * {@code head_gestures} carries its direction in {@code mode} rather than in data. Those quirks are
 * the device's, and this is where they are pinned down. */
final class GlassesSettingWrite {
    /** The seven values the official settings page offers for automatic sleep. */
    private static final int[] AUTO_LOCK = {5, 10, 15, 25, 40, 60, 120};

    static final class Refusal extends Exception {
        Refusal(String message) { super(message); }
    }

    /** {@code type} for the packet this request needs. Brightness is the odd one: type 2, while
     * every other setting write is type 5. */
    static int type(String target) throws Refusal {
        if ("brightness".equals(target)) return 2;
        if (known(target)) return 5;
        throw new Refusal("未知的设置项");
    }

    static boolean known(String target) {
        return "brightness".equals(target) || "auto_lock".equals(target) || "wakeup".equals(target)
            || "crown".equals(target) || "head".equals(target);
    }

    /** Builds the packet body. Throws {@link Refusal} with a message meant for the user. */
    static JSONObject body(String target, JSONObject request) throws Exception {
        if (request == null) throw new Refusal("缺少设置内容");
        if ("brightness".equals(target)) {
            int value = require(request, "value", "亮度");
            // 1..17, with UI 0% at 1 and 100% at 17.
            if (value < 1 || value > 17) throw new Refusal("亮度需在 1–17 之间");
            return packet("brightness_change", value, 0, "");
        }
        if ("auto_lock".equals(target)) {
            int seconds = require(request, "value", "自动息屏");
            boolean allowed = false;
            for (int option : AUTO_LOCK) if (option == seconds) allowed = true;
            if (!allowed) throw new Refusal("自动息屏只能是 5/10/15/25/40/60/120 秒");
            // mode and data are explicitly null for this one, not omitted.
            return new JSONObject().put("cmd", "auto_lock_time").put("payload",
                new JSONObject().put("value", seconds).put("mode", JSONObject.NULL).put("data", JSONObject.NULL));
        }
        if ("wakeup".equals(target)) {
            int headSwitch = require(request, "headup_switch", "抬头唤醒开关");
            int degree = require(request, "headup_degree", "抬头唤醒角度");
            int crownSwitch = require(request, "crown_switch", "息屏互动");
            if (headSwitch != 0 && headSwitch != 1) throw new Refusal("抬头唤醒开关只能是 0 或 1");
            // 0..90 integer degrees.
            if (degree < 0 || degree > 90) throw new Refusal("抬头唤醒角度需在 0–90 之间");
            // 0..3 mode, not a boolean.
            if (crownSwitch < 0 || crownSwitch > 3) throw new Refusal("息屏互动需在 0–3 之间");
            String data = new JSONObject().put("headup_switch", headSwitch)
                .put("headup_degree", degree).put("crown_switch", crownSwitch).toString();
            return packet("wakeup_config", 0, 0, data);
        }
        if ("crown".equals(target)) {
            int direction = require(request, "direction", "表冠方向");
            int doubleTap = require(request, "double", "表冠双击");
            int longPress = require(request, "longPress", "息屏长按");
            // 1=自然 / 0=标准.
            if (direction != 0 && direction != 1) throw new Refusal("表冠方向只能是 0 或 1");
            action(doubleTap, true, "表冠双击");
            action(longPress, false, "息屏长按");
            // data is a JSON *string*, not a nested object.
            String data = new JSONObject().put("double", doubleTap).put("longPress", longPress).toString();
            return packet("crown_config", direction, 0, data);
        }
        if ("head".equals(target)) {
            int enabled = require(request, "enabled", "头控开关");
            int mode = require(request, "mode", "头控方向");
            if (enabled != 0 && enabled != 1) throw new Refusal("头控开关只能是 0 或 1");
            if (mode != 0 && mode != 1) throw new Refusal("头控方向只能是 0 或 1");
            // direction rides in mode; data stays empty.
            return packet("head_gestures", enabled, mode, "");
        }
        throw new Refusal("未知的设置项");
    }

    /** The ten-item action enum, closed 2026-09-19 by comparing the official double-tap page
     * against the reverse-engineered values. Item 6 (全天智记) is offered on double tap only --
     * the screen-off long-press page does not list it. */
    private static void action(int value, boolean doubleTap, String label) throws Refusal {
        boolean valid = value >= 0 && value <= 9 && value != 6;
        if (value == 6 && doubleTap) valid = true;
        if (!valid) throw new Refusal(label + "的动作取值无效");
    }

    private static int require(JSONObject request, String key, String label) throws Refusal {
        Object value = request.opt(key);
        // A missing field is not zero. Writing a silent default to the glasses would change a
        // setting the user never touched.
        if (!(value instanceof Number)) throw new Refusal("缺少" + label);
        double number = ((Number) value).doubleValue();
        if (number != Math.floor(number) || Double.isInfinite(number)) throw new Refusal(label + "必须是整数");
        return (int) number;
    }

    private static JSONObject packet(String cmd, int value, int mode, String data) throws Exception {
        return new JSONObject().put("cmd", cmd).put("payload",
            new JSONObject().put("value", value).put("mode", mode).put("data", data));
    }

    private GlassesSettingWrite() {}
}
