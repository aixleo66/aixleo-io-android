package dev.xr.rayneo.probe;

/** Presentation only; saved/busy admission remains owned by GlassesRecorder. */
final class RecordingProgress {
    static String savingTitle(String stage) {
        if ("tail_wait".equals(stage)) return "已收到结束回报，等待尾部数据";
        if ("raw_validation".equals(stage)) return "正在校验并保存录音原件";
        if ("receipt_before_decode".equals(stage)) return "原件已校验，正在记录保存信息";
        if ("receipt_before_package".equals(stage)) return "原件已校验，正在记录保存信息";
        if ("packaging".equals(stage)) return "原件已保存，正在封装为可播放音频";
        // Recordings saved before 09-23 went through a whole-file decode; kept for their receipts.
        if ("decoding".equals(stage)) return "原件已保存，正在生成可播放音频";
        return "正在校验与保存";
    }
}
