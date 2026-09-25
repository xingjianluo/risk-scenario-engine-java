package com.github.xingjianluo.riskscenarioenginejava.domain;

import java.util.List;

/**
 * 风险结论。comparable=false 时说明结论不可与既有版本直接比较，warnings 给出依据。
 */
public record RiskReport(
        String portfolioId,
        String scenarioId,
        String scenarioVersion,
        double baseValue,
        double shockedValue,
        double scenarioLoss,
        LossDistribution historicalLosses,
        boolean comparable,
        List<String> warnings) {
}
