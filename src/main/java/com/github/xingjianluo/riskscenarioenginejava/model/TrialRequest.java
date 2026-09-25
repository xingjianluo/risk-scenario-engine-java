package com.github.xingjianluo.riskscenarioenginejava.model;

/**
 * 试算请求。
 *
 * @param timeoutMs  单次计算超时（毫秒），可空使用默认值
 * @param maxAttempts 技术失败时的最大尝试次数（含首次），可空使用默认值
 */
public record TrialRequest(String requestId,
                           Portfolio portfolio,
                           java.util.List<MarketSnapshot> snapshots,
                           ScenarioDefinition scenario,
                           Integer timeoutMs,
                           Integer maxAttempts) {
}
