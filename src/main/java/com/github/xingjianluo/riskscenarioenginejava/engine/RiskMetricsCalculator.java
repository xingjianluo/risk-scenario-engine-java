package com.github.xingjianluo.riskscenarioenginejava.engine;

import com.github.xingjianluo.riskscenarioenginejava.domain.LossDistribution;
import com.github.xingjianluo.riskscenarioenginejava.domain.RejectionReason;
import com.github.xingjianluo.riskscenarioenginejava.validation.DataValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 尾部风险指标（损失为正数，升序排列）：
 * VaR(q) 为经验分位数（最近秩法），ES(q) 为超过 VaR 部分的均值（含 VaR 自身所在尾部段）。
 * 尾部样本数（(1-q)*n 向上取整）不足 minTailSamples 时拒绝 INSUFFICIENT_TAIL_SAMPLES。
 */
@Component
public class RiskMetricsCalculator {

    private static final Logger log = LoggerFactory.getLogger(RiskMetricsCalculator.class);

    public LossDistribution summarize(List<Double> losses, int minTailSamples) {
        if (losses == null || losses.isEmpty()) {
            throw new DataValidationException(RejectionReason.INSUFFICIENT_HISTORY, "no loss samples");
        }
        List<Double> sorted = new ArrayList<>(losses);
        Collections.sort(sorted);
        int n = sorted.size();
        requireTailSamples(n, 0.95, minTailSamples);
        requireTailSamples(n, 0.99, minTailSamples);
        double mean = sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        return new LossDistribution(
                List.copyOf(sorted), n, mean,
                var(sorted, 0.95), es(sorted, 0.95),
                var(sorted, 0.99), es(sorted, 0.99));
    }

    private void requireTailSamples(int n, double q, int minTailSamples) {
        int tail = (int) Math.ceil((1.0 - q) * n);
        if (tail < minTailSamples) {
            log.info("risk metrics rejected: reason={} n={} q={} tailSamples={} required={}",
                    RejectionReason.INSUFFICIENT_TAIL_SAMPLES, n, q, tail, minTailSamples);
            throw new DataValidationException(RejectionReason.INSUFFICIENT_TAIL_SAMPLES,
                    "tail at q=" + q + " has " + tail + " samples, requires >= " + minTailSamples
                            + " (n=" + n + ")");
        }
    }

    /** 最近秩分位数：ceil(q*n) 对应的升序元素（1-based）。 */
    private double var(List<Double> sorted, double q) {
        int rank = Math.max(1, (int) Math.ceil(q * sorted.size()));
        return sorted.get(rank - 1);
    }

    /** ES：秩 >= VaR 秩的所有样本均值。 */
    private double es(List<Double> sorted, double q) {
        int rank = Math.max(1, (int) Math.ceil(q * sorted.size()));
        double sum = 0.0;
        for (int i = rank - 1; i < sorted.size(); i++) {
            sum += sorted.get(i);
        }
        return sum / (sorted.size() - rank + 1);
    }
}
