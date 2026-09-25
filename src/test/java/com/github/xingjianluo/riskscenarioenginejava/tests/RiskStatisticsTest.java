package com.github.xingjianluo.riskscenarioenginejava.tests;

import com.github.xingjianluo.riskscenarioenginejava.engine.RiskStatistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** VaR/ES 次序统计量口径测试（无插值、ES含切点）。 */
class RiskStatisticsTest {

    private static final Logger LOG = Logger.getLogger(RiskStatisticsTest.class.getName());

    private final RiskStatistics statistics = new RiskStatistics();

    @Test
    @DisplayName("20个样本：VaR95取第19个次序量(=-1即第2小损失0.81)，ES95为顶部2个均值")
    void varAndEsOrderStatistic() {
        double[] samples = new double[20];
        for (int i = 0; i < 20; i++) {
            samples[i] = i / 100.0; // 0.00 .. 0.19
        }
        RiskStatistics.TailMetric m95 = statistics.quantileAndEs(samples, 0.95);
        RiskStatistics.TailMetric m99 = statistics.quantileAndEs(samples, 0.99);
        LOG.info("输入摘要: 20个有序损失样本[0.00..0.19], 场景版本=" + TestData.V_HIST
                + "; 判定依据: ceil(0.95*20)=19 -> 索引18值0.18, ES95=(0.18+0.19)/2; 拒绝原因: 无");
        assertEquals(0.18, m95.varValue(), 1e-12);
        assertEquals(0.185, m95.esValue(), 1e-12);
        assertEquals(0.19, m99.varValue(), 1e-12);
        assertEquals(0.19, m99.esValue(), 1e-12);
    }

    @Test
    @DisplayName("统计不修改入参数组")
    void inputArrayNotMutated() {
        double[] samples = {0.3, 0.1, 0.2};
        statistics.quantileAndEs(samples, 0.95);
        assertEquals(0.3, samples[0], 0);
        assertTrue(samples[0] == 0.3 && samples[1] == 0.1 && samples[2] == 0.2);
    }
}
