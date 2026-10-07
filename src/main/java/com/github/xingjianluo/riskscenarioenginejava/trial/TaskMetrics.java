package com.github.xingjianluo.riskscenarioenginejava.trial;

import java.util.Map;

/**
 * 任务层容量与生命周期指标（骨架，实现后补）。
 */
public record TaskMetrics(
        long accepted,
        long deduplicated,
        long capacityRejected,
        long cancelled,
        long completed,
        long queuedNow,
        long runningNow) {

    public Map<String, Long> asMap() {
        return Map.of(
                "accepted", accepted,
                "deduplicated", deduplicated,
                "capacityRejected", capacityRejected,
                "cancelled", cancelled,
                "completed", completed,
                "queuedNow", queuedNow,
                "runningNow", runningNow);
    }
}
