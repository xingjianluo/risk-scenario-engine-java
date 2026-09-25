package com.github.xingjianluo.riskscenarioenginejava.trial;

import com.github.xingjianluo.riskscenarioenginejava.domain.RejectionReason;
import com.github.xingjianluo.riskscenarioenginejava.domain.RiskReport;
import com.github.xingjianluo.riskscenarioenginejava.domain.TrialStatus;

/**
 * 试算结果：成功与失败可区分；rejectionReason 仅在 REJECTED 时非空。
 */
public record TrialResult(
        String idempotencyKey,
        TrialStatus status,
        RiskReport report,
        RejectionReason rejectionReason,
        String detail) {

    public static TrialResult success(String key, RiskReport report) {
        return new TrialResult(key, TrialStatus.SUCCEEDED, report, null, null);
    }

    public static TrialResult rejected(String key, RejectionReason reason, String detail) {
        return new TrialResult(key, TrialStatus.REJECTED, null, reason, detail);
    }

    public static TrialResult failed(String key, String detail) {
        return new TrialResult(key, TrialStatus.FAILED, null, null, detail);
    }

    public static TrialResult timedOut(String key, long timeoutMs) {
        return new TrialResult(key, TrialStatus.TIMED_OUT, null, null, "timeout after " + timeoutMs + "ms");
    }
}
