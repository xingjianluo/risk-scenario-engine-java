package com.github.xingjianluo.riskscenarioenginejava.task;

import java.util.Map;

/**
 * 任务层指标快照。自洽约束：
 * submitted = queued + running + succeeded + rejected + failed + timedOut + cancelled，
 * 其中 rejected 含容量拒绝（capacityRejected 为其子集，便于区分拒绝原因）。
 */
public record TaskMetrics(
        long submitted,
        long deduplicated,
        long queued,
        long running,
        long succeeded,
        long rejected,
        long capacityRejected,
        long failed,
        long timedOut,
        long cancelled) {

    public Map<String, Long> asMap() {
        return Map.of(
                "submitted", submitted,
                "deduplicated", deduplicated,
                "queued", queued,
                "running", running,
                "succeeded", succeeded,
                "rejected", rejected,
                "capacityRejected", capacityRejected,
                "failed", failed,
                "timedOut", timedOut,
                "cancelled", cancelled);
    }
}
