package com.github.xingjianluo.riskscenarioenginejava.domain;

/**
 * 可区分的输入/计算拒绝原因。
 */
public enum RejectionReason {
    EMPTY_PORTFOLIO,
    MISSING_FIELD,
    NON_POSITIVE_PRICE,
    UNKNOWN_ASSET,
    DUPLICATE_SNAPSHOT_DATE,
    UNSORTED_SNAPSHOTS_NORMALIZED,
    INVALID_CORRELATION_MATRIX,
    INSUFFICIENT_HISTORY,
    INSUFFICIENT_TAIL_SAMPLES,
    SCENARIO_VERSION_MISMATCH
}
