package com.github.xingjianluo.riskscenarioenginejava.engine;

import com.github.xingjianluo.riskscenarioenginejava.model.Holding;
import com.github.xingjianluo.riskscenarioenginejava.model.MarketSnapshot;
import com.github.xingjianluo.riskscenarioenginejava.model.Portfolio;
import com.github.xingjianluo.riskscenarioenginejava.model.RejectCode;
import com.github.xingjianluo.riskscenarioenginejava.model.ScenarioDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 场景模拟器：把 {@link ScenarioDefinition} 展开为组合损失样本（正数=亏损）。
 *
 * <ul>
 *   <li>{@code SCENARIO_SHOCK}：确定性单样本，直接施加场景给定的收益率冲击；</li>
 *   <li>{@code CORRELATION_ADJUST}：按给定相关结构做可复现高斯蒙特卡洛，
 *       波动率统一从多期快照历史价格估计，随机数仅由 {@code seed} 决定；</li>
 *   <li>{@code HISTORICAL_SIMULATION}：以场景内嵌的历史收益率序列逐期重估，
 *       样本数即序列长度，结论随场景版本可复现。</li>
 * </ul>
 *
 * <p>样本下限、矩阵合法性等问题返回确定性拒绝，不抛异常、不静默跳过。</p>
 */
@Component
public class ScenarioSimulator {

    /** 尾部指标所需的最小样本数（95%/99% 口径统一要求）。 */
    public static final int MIN_TAIL_SAMPLES = 20;

    private final ValuationEngine valuationEngine;
    private final MatrixValidator matrixValidator;

    public ScenarioSimulator(ValuationEngine valuationEngine, MatrixValidator matrixValidator) {
        this.valuationEngine = valuationEngine;
        this.matrixValidator = matrixValidator;
    }

    /**
     * @param sortedSnapshots 已按日期升序归一化的快照（至少含最新一期）
     * @param latestSnapshot  最新一期快照
     */
    public SimulationOutcome simulate(Portfolio portfolio,
                                      List<MarketSnapshot> sortedSnapshots,
                                      MarketSnapshot latestSnapshot,
                                      ScenarioDefinition scenario) {
        Map<String, Double> latestPrices = latestSnapshot.prices();
        return switch (scenario.type()) {
            case SCENARIO_SHOCK -> simulateShock(portfolio, latestPrices, scenario);
            case CORRELATION_ADJUST -> simulateCorrelation(portfolio, sortedSnapshots, latestPrices, scenario);
            case HISTORICAL_SIMULATION -> simulateHistorical(portfolio, latestPrices, scenario);
        };
    }

    /* ----------------------------- 场景冲击 ----------------------------- */

    private SimulationOutcome simulateShock(Portfolio portfolio,
                                            Map<String, Double> latestPrices,
                                            ScenarioDefinition scenario) {
        Map<String, Double> returns = new HashMap<>(scenario.shocks());
        double pnl = valuationEngine.pnlForReturns(portfolio, latestPrices, returns);
        List<String> notes = List.of("确定性场景冲击：单样本，PnL=Σ数量×最新价×冲击幅度，不计算VaR/ES");
        return SimulationOutcome.ok(new double[]{-pnl}, notes);
    }

    /* --------------------------- 相关性调整 ----------------------------- */

    private SimulationOutcome simulateCorrelation(Portfolio portfolio,
                                                  List<MarketSnapshot> sortedSnapshots,
                                                  Map<String, Double> latestPrices,
                                                  ScenarioDefinition scenario) {
        if (scenario.sampleCount() < MIN_TAIL_SAMPLES) {
            return SimulationOutcome.rejected(RejectCode.TAIL_SAMPLES_INSUFFICIENT,
                    "相关性调整场景抽样次数 " + scenario.sampleCount()
                            + " 少于尾部指标下限 " + MIN_TAIL_SAMPLES);
        }

        Set<String> held = new LinkedHashSet<>();
        for (Holding h : portfolio.holdings()) {
            held.add(h.instrumentId());
        }
        RejectCode matrixIssue = matrixValidator.validate(
                scenario.correlationInstruments(), scenario.correlation(), held);
        if (matrixIssue != null) {
            return SimulationOutcome.rejected(matrixIssue, "相关性矩阵不合法：" + matrixIssue);
        }

        List<String> instruments = scenario.correlationInstruments();
        double[][] vols = historicalVolatilities(instruments, sortedSnapshots);
        if (vols == null) {
            return SimulationOutcome.rejected(RejectCode.VOLATILITY_SAMPLES_INSUFFICIENT,
                    "估计历史波动率至少需要 2 期含全部相关标的价格的快照");
        }

        double[][] correlated = cholesky(scenario.correlation());
        Random rng = new Random(scenario.seed());
        int m = instruments.size();
        int samples = scenario.sampleCount();
        double[] losses = new double[samples];

        for (int s = 0; s < samples; s++) {
            Map<String, Double> sampleReturns = new HashMap<>();
            double[] independent = new double[m];
            for (int k = 0; k < m; k++) {
                independent[k] = rng.nextGaussian();
            }
            for (int i = 0; i < m; i++) {
                double z = 0.0;
                for (int k = 0; k <= i; k++) {
                    z += correlated[i][k] * independent[k];
                }
                double vol = vols[i][0];
                if (vol == 0.0) {
                    vol = vols[i][1]; // 近零方差时使用均值绝对收益率作为尺度
                }
                sampleReturns.put(instruments.get(i), z * vol);
            }
            double pnl = valuationEngine.pnlForReturns(portfolio, latestPrices, sampleReturns);
            losses[s] = -pnl;
        }

        List<String> notes = List.of(
                "相关性调整：高斯蒙特卡洛 " + samples + " 次抽样，种子=" + scenario.seed() + "（可复现）",
                "波动率口径：多期快照对数收益率样本标准差；零方差时回退为均值绝对收益率",
                "未进入相关性矩阵的持有标的按零收益处理");
        return SimulationOutcome.ok(losses, notes);
    }

    /* --------------------------- 历史模拟 ------------------------------- */

    private SimulationOutcome simulateHistorical(Portfolio portfolio,
                                                 Map<String, Double> latestPrices,
                                                 ScenarioDefinition scenario) {
        Map<String, List<Double>> seriesByInstrument = scenario.historicalReturns();
        Integer length = null;
        Set<String> held = new LinkedHashSet<>();
        for (Holding h : portfolio.holdings()) {
            held.add(h.instrumentId());
        }
        for (String instrument : held) {
            List<Double> series = seriesByInstrument.get(instrument);
            if (series == null) {
                return SimulationOutcome.rejected(RejectCode.HISTORICAL_INSTRUMENT_MISSING,
                        "历史收益率序列缺少持仓标的：" + instrument);
            }
            for (Double r : series) {
                if (r == null || !Double.isFinite(r)) {
                    return SimulationOutcome.rejected(RejectCode.HISTORICAL_RETURN_NONFINITE,
                            "标的 " + instrument + " 的历史收益率存在空值或非有限值");
                }
            }
            if (length == null) {
                length = series.size();
            } else if (series.size() != length) {
                return SimulationOutcome.rejected(RejectCode.HISTORICAL_SERIES_LENGTH_MISMATCH,
                        "标的 " + instrument + " 的历史序列长度 " + series.size()
                                + " 与先前标的 " + length + " 不一致");
            }
        }
        int n = length;
        if (n < MIN_TAIL_SAMPLES) {
            return SimulationOutcome.rejected(RejectCode.TAIL_SAMPLES_INSUFFICIENT,
                    "历史样本数 " + n + " 少于尾部指标下限 " + MIN_TAIL_SAMPLES);
        }

        double[] losses = new double[n];
        for (int t = 0; t < n; t++) {
            Map<String, Double> periodReturns = new HashMap<>();
            for (String instrument : held) {
                periodReturns.put(instrument, seriesByInstrument.get(instrument).get(t));
            }
            losses[t] = -valuationEngine.pnlForReturns(portfolio, latestPrices, periodReturns);
        }

        List<String> notes = List.of(
                "历史模拟：逐期重估，样本数=" + n + "，样本计数=期数（不重复计入）",
                "样本与序列顺序完全取自场景定义，结论随场景版本可复现");
        return SimulationOutcome.ok(losses, notes);
    }

    /* --------------------------- 辅助方法 ------------------------------- */

    /**
     * 从升序快照估计各标的对数收益率波动率。
     *
     * @return 长度为标的数的数组，每项 [样本标准差, 均值绝对收益率]；
     *         可用于估计的连续有效价格对不足 2 个时返回 {@code null}
     */
    private double[][] historicalVolatilities(List<String> instruments,
                                              List<MarketSnapshot> sortedSnapshots) {
        int periods = sortedSnapshots.size() - 1;
        if (periods < 2) {
            return null;
        }
        double[][] result = new double[instruments.size()][2];
        for (int idx = 0; idx < instruments.size(); idx++) {
            String instrument = instruments.get(idx);
            List<Double> logReturns = new ArrayList<>(periods);
            Double previous = priceOf(sortedSnapshots.get(0), instrument);
            if (previous == null) {
                return null;
            }
            for (int t = 1; t < sortedSnapshots.size(); t++) {
                Double current = priceOf(sortedSnapshots.get(t), instrument);
                if (current == null) {
                    return null;
                }
                logReturns.add(Math.log(current / previous));
                previous = current;
            }
            double mean = 0.0;
            for (double r : logReturns) {
                mean += r;
            }
            mean /= logReturns.size();
            double variance = 0.0;
            double absSum = 0.0;
            for (double r : logReturns) {
                variance += (r - mean) * (r - mean);
                absSum += Math.abs(r);
            }
            result[idx][0] = Math.sqrt(variance / (logReturns.size() - 1));
            result[idx][1] = absSum / logReturns.size();
        }
        return result;
    }

    private Double priceOf(MarketSnapshot snapshot, String instrument) {
        Double price = snapshot.prices().get(instrument);
        if (price == null || !Double.isFinite(price) || price <= 0.0) {
            return null;
        }
        return price;
    }

    private double[][] cholesky(double[][] matrix) {
        int n = matrix.length;
        double[][] lower = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = matrix[i][j];
                for (int k = 0; k < j; k++) {
                    sum -= lower[i][k] * lower[j][k];
                }
                if (i == j) {
                    lower[i][j] = Math.sqrt(Math.max(sum, 0.0));
                } else {
                    lower[i][j] = lower[j][j] == 0.0 ? 0.0 : sum / lower[j][j];
                }
            }
        }
        return lower;
    }
}
