package com.github.xingjianluo.riskscenarioenginejava.trial;

import java.util.Map;

/**
 * 试算指标快照：提交/去重/成功/拒绝/失败/超时计数。
 */
public record TrialMetrics(
        long submitted,
        long deduplicated,
        long succeeded,
        long rejected,
        long failed,
        long timedOut) {

    public Map<String, Long> asMap() {
        return Map.of(
                "submitted", submitted,
                "deduplicated", deduplicated,
                "succeeded", succeeded,
                "rejected", rejected,
                "failed", failed,
                "timedOut", timedOut);
    }
}
