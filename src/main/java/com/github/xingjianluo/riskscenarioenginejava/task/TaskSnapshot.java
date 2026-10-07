package com.github.xingjianluo.riskscenarioenginejava.task;

import com.github.xingjianluo.riskscenarioenginejava.domain.TrialStatus;
import com.github.xingjianluo.riskscenarioenginejava.trial.TrialResult;

/**
 * 任务状态快照：凭 taskId 查询返回的稳定视图。
 * 同一 taskId 反复查询结果一致；查询本身不触发任何计算。
 */
public record TaskSnapshot(
        String taskId,
        TrialStatus status,
        TrialResult result,
        long submittedAtMs,
        Long startedAtMs,
        Long finishedAtMs,
        String detail) {
}
