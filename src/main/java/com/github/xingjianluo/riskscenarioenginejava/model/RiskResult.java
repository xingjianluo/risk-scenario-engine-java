package com.github.xingjianluo.riskscenarioenginejava.model;

import java.util.List;

/**
 * 一次试算的确定结论。
 *
 * @param inputDigest     归一化输入摘要（SHA-256 前 16 位），用于幂等与可追溯
 * @param notes           判定依据与处理说明（如快照已按日期重排）
 * @param sampleCount     实际计入的样本数；拒绝/失败时为 0
 * @param attempt         实际执行的尝试次数（技术失败重试时递增）
 */
public record RiskResult(String requestId,
                         String portfolioId,
                         String scenarioId,
                         String scenarioVersion,
                         ResultStatus status,
                         RejectCode rejectCode,
                         String rejectReason,
                         double baseValue,
                         double stressedPnl,
                         double var95,
                         double es95,
                         double var99,
                         double es99,
                         int sampleCount,
                         String inputDigest,
                         List<String> notes,
                         long computedAt,
                         int attempt) {

    /** 两期结论可比性判定结果。 */
    public record Comparability(boolean comparable, String reason) {
    }

    /**
     * 标注两期正常结论是否可比：
     * 仅当两者均 ACCEPTED、同场景、同场景版本、同样本计数时可比；
     * 场景版本变化等情况一律标注“不可比”并给出原因，绝不静默直接对比。
     */
    public Comparability comparabilityWith(RiskResult other) {
        if (other == null) {
            return new Comparability(false, "缺少对照结论");
        }
        if (this.status() != ResultStatus.ACCEPTED || other.status() != ResultStatus.ACCEPTED) {
            return new Comparability(false,
                    "存在非正常（REJECTED/FAILED）结论，不进行指标对比");
        }
        if (!eq(this.scenarioId(), other.scenarioId())) {
            return new Comparability(false, "场景不同（"
                    + this.scenarioId() + " vs " + other.scenarioId() + "），不可比");
        }
        if (!eq(this.scenarioVersion(), other.scenarioVersion())) {
            return new Comparability(false, "场景版本变化（"
                    + this.scenarioVersion() + " -> " + other.scenarioVersion()
                    + "），结论不可比；历史结论可按各自版本复现");
        }
        if (this.sampleCount() != other.sampleCount()) {
            return new Comparability(false, "样本计数不一致（"
                    + this.sampleCount() + " vs " + other.sampleCount() + "），不可比");
        }
        return new Comparability(true, "同场景同版本同样本计数，指标可比");
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
