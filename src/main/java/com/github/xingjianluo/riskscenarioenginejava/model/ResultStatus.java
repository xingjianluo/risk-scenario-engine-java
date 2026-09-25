package com.github.xingjianluo.riskscenarioenginejava.model;

/** 试算结论：正常接受（含风险指标）、确定性拒绝（数据/口径问题）、技术失败（可重试）。 */
public enum ResultStatus {
    ACCEPTED,
    REJECTED,
    FAILED
}
