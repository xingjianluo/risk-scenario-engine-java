package com.github.xingjianluo.riskscenarioenginejava.domain;

import java.util.Map;

/**
 * 场景定义：shocks 为 assetId -> 相对冲击（如 -0.10 表示下跌 10%）。
 * version 用于结论可比性与复现；minHistoryPoints/minTailSamples 为历史模拟最低样本要求。
 */
public record ScenarioDefinition(
        String scenarioId,
        String version,
        Map<String, Double> shocks,
        CorrelationMatrix correlation,
        int minHistoryPoints,
        int minTailSamples) {
}
