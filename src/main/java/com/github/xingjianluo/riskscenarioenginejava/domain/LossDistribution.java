package com.github.xingjianluo.riskscenarioenginejava.domain;

import java.util.List;

/**
 * 损失分布（损失为正数）及尾部风险指标。
 */
public record LossDistribution(
        List<Double> sortedLosses,
        int sampleCount,
        double mean,
        double var95,
        double es95,
        double var99,
        double es99) {
}
