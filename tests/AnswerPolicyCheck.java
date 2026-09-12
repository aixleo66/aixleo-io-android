package dev.xr.rayneo.probe;
final class AnswerPolicyCheck {
    public static void main(String[] args) {
        for (String bad : new String[]{"实在不行，塑料袋套头应急也行。", "塑 料 袋套在头上", "用塑料袋遮挡口鼻"})
            if (AnswerPolicy.canDeliver(bad)) throw new AssertionError("Observed unsafe advice accepted");
        for (String safe : new String[]{"就近避雨，购买雨伞或雨衣，也可以打车。", "可用塑料袋装书，避免淋湿。", "在下一个路口左转。"})
            if (!AnswerPolicy.canDeliver(safe)) throw new AssertionError("Unrelated answer blocked");
        System.out.println("passed");
    }
}
