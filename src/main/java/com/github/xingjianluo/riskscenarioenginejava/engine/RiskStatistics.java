package com.github.xingjianluo.riskscenarioenginejava.engine;

import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 损失分布统计（历史模拟法口径）。
 *
 * <p>输入为组合损失样本（正数表示亏损，负数表示盈利）：</p>
 * <ul>
 *   <li>VaR<sub>q</sub>：升序排列后取第 ⌈q·N⌉ 个次序统计量（1-based，即索引 ⌈q·N⌉−1）；</li>
 *   <li>ES<sub>q</sub>：VaR 切点及以上损失（含切点）的算术平均，即条件尾部期望；</li>
 *   <li>不做分布假设、不做插值，结论可直接由样本次序解释与复现。</li>
 * </ul>
 */
@Component
public class RiskStatistics {

    public record TailMetric(double varValue, double esValue) {
    }

    /**
     * @param lossSamples 损失样本，至少 1 个（样本下限由上层统一校验）
     * @param quantile    置信水平，如 0.95、0.99
     */
    public TailMetric quantileAndEs(double[] lossSamples, double quantile) {
        double[] sorted = sortedCopy(lossSamples);
        int n = sorted.length;
        int index = (int) Math.ceil(quantile * n) - 1;
        if (index < 0) {
            index = 0;
        }
        if (index > n - 1) {
            index = n - 1;
        }
        double varValue = sorted[index];
        double tailSum = 0.0;
        for (int i = index; i < n; i++) {
            tailSum += sorted[i];
        }
        double esValue = tailSum / (n - index);
        return new TailMetric(varValue, esValue);
    }

    /** 升序排序的防御性副本，不修改入参数组。 */
    public double[] sortedCopy(double[] samples) {
        double[] copy = samples.clone();
        Arrays.sort(copy);
        return copy;
    }
}
