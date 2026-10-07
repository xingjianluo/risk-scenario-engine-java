package com.github.xingjianluo.riskscenarioenginejava.trial;

import com.github.xingjianluo.riskscenarioenginejava.domain.TrialStatus;

/**
 * 任务化试算的查询快照。
 *
 * <p>status 取值：QUEUED（排队中）、RUNNING（执行中）、SUCCEEDED/REJECTED/FAILED/TIMED_OUT
 * （终态，与底层 {@link TrialResult} 一致）、CANCELLED（已取消，独立终态）。
 * status 为 null 表示编号未知（未找到票据）。进行中的快照 result 为 null；
 * 终态快照（除 CANCELLED 外）携带完整 {@link TrialResult}。
 */
public record TrialTaskView(
        String ticketId,
        TrialStatus status,
        TrialResult result,
        String detail) {

    /** 编号未知时的可识别快照：status=null，detail 说明原因。 */
    public static TrialTaskView notFound(String ticketId) {
        return new TrialTaskView(ticketId, null, null, "unknown ticket: " + ticketId);
    }

    /** 是否为终态（含 CANCELLED）；未知编号视为非终态。 */
    public boolean isTerminal() {
        return status == TrialStatus.SUCCEEDED || status == TrialStatus.REJECTED
                || status == TrialStatus.FAILED || status == TrialStatus.TIMED_OUT
                || status == TrialStatus.CANCELLED;
    }
}
