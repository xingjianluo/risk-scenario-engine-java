package com.github.xingjianluo.riskscenarioenginejava.trial;

import com.github.xingjianluo.riskscenarioenginejava.domain.MarketSnapshot;
import com.github.xingjianluo.riskscenarioenginejava.domain.Portfolio;
import com.github.xingjianluo.riskscenarioenginejava.domain.ScenarioDefinition;

import java.util.List;

/**
 * 试算请求。idempotencyKey 相同的重复提交返回同一结果，不重复计入指标。
 */
public record TrialRequest(
        String idempotencyKey,
        Portfolio portfolio,
        List<MarketSnapshot> snapshots,
        ScenarioDefinition scenario,
        String requestedScenarioVersion,
        long timeoutMs) {
}
