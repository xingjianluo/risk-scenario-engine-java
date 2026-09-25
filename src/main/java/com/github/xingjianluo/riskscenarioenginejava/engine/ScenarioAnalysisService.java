package com.github.xingjianluo.riskscenarioenginejava.engine;

import com.github.xingjianluo.riskscenarioenginejava.domain.*;
import com.github.xingjianluo.riskscenarioenginejava.validation.DataValidationException;
import com.github.xingjianluo.riskscenarioenginejava.validation.InputValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 场景分析编排：
 * 1) 校验并归一化输入（乱序快照确定性排序，非法数据拒绝）；
 * 2) 场景冲击估值，若有相关性矩阵则做相关性调整；
 * 3) 历史模拟得到损失分布与尾部指标；
 * 4) 版本不匹配明确拒绝，未锁定版本标注不可比。
 * 同一输入重复计算结果确定（无随机源、无外部依赖）。
 */
@Service
public class ScenarioAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioAnalysisService.class);

    private final InputValidator validator;
    private final ValuationEngine valuationEngine;
    private final RiskMetricsCalculator riskMetrics;

    public ScenarioAnalysisService(InputValidator validator, ValuationEngine valuationEngine,
                                   RiskMetricsCalculator riskMetrics) {
        this.validator = validator;
        this.valuationEngine = valuationEngine;
        this.riskMetrics = riskMetrics;
    }

    public RiskReport analyze(Portfolio portfolio, List<MarketSnapshot> snapshots,
                              ScenarioDefinition scenario, String requestedVersion) {
        log.info("analyze start: portfolio={} positions={} snapshots={} scenario={} version={} requestedVersion={}",
                portfolio == null ? null : portfolio.portfolioId(),
                portfolio == null ? 0 : portfolio.positions().size(),
                snapshots == null ? 0 : snapshots.size(),
                scenario == null ? null : scenario.scenarioId(),
                scenario == null ? null : scenario.version(),
                requestedVersion);

        validator.validatePortfolio(portfolio);
        List<String> warnings = new ArrayList<>();
        List<MarketSnapshot> ordered = validator.validateAndNormalizeSnapshots(snapshots, warnings);
        validator.validateScenario(scenario, portfolio);

        // 版本口径：锁定版本且不一致 -> 拒绝；未锁定 -> 标注不可比
        boolean comparable = true;
        if (requestedVersion != null && !requestedVersion.equals(scenario.version())) {
            log.info("analyze rejected: reason={} requested={} actual={}",
                    RejectionReason.SCENARIO_VERSION_MISMATCH, requestedVersion, scenario.version());
            throw new DataValidationException(RejectionReason.SCENARIO_VERSION_MISMATCH,
                    "requested scenario version " + requestedVersion + " != actual " + scenario.version());
        }
        if (requestedVersion == null) {
            comparable = false;
            warnings.add("scenario version not pinned by caller; result not comparable across versions");
        }

        MarketSnapshot latest = ordered.get(ordered.size() - 1);
        double baseValue = valuationEngine.value(portfolio, latest);

        // 场景冲击 + 相关性调整
        double shockedValue = valuationEngine.shockedValue(portfolio, latest, scenario.shocks());
        double scenarioLoss = baseValue - shockedValue;
        if (scenario.correlation() != null) {
            scenarioLoss = correlationAdjustedLoss(portfolio, latest, scenario);
            warnings.add("scenario loss adjusted by correlation matrix (diversified)");
        }

        // 历史模拟
        LossDistribution distribution = null;
        if (ordered.size() < 2) {
            if (scenario.minHistoryPoints() > 0) {
                log.info("analyze rejected: reason={} snapshots={} required={}",
                        RejectionReason.INSUFFICIENT_HISTORY, ordered.size(), scenario.minHistoryPoints());
                throw new DataValidationException(RejectionReason.INSUFFICIENT_HISTORY,
                        "only " + ordered.size() + " snapshot(s), need at least 2 for returns and "
                                + scenario.minHistoryPoints() + " return points");
            }
            warnings.add("single snapshot: historical simulation skipped (minHistoryPoints=0)");
        } else {
            List<Double> losses = historicalLosses(portfolio, ordered, baseValue);
            if (losses.size() < scenario.minHistoryPoints()) {
                log.info("analyze rejected: reason={} returnPoints={} required={}",
                        RejectionReason.INSUFFICIENT_HISTORY, losses.size(), scenario.minHistoryPoints());
                throw new DataValidationException(RejectionReason.INSUFFICIENT_HISTORY,
                        losses.size() + " return points < required " + scenario.minHistoryPoints());
            }
            distribution = riskMetrics.summarize(losses, scenario.minTailSamples());
        }

        log.info("analyze done: portfolio={} scenario={} v{} base={} scenarioLoss={} histSamples={} comparable={} warnings={}",
                portfolio.portfolioId(), scenario.scenarioId(), scenario.version(), baseValue, scenarioLoss,
                distribution == null ? 0 : distribution.sampleCount(), comparable, warnings);

        return new RiskReport(portfolio.portfolioId(), scenario.scenarioId(), scenario.version(),
                baseValue, shockedValue, scenarioLoss, distribution, comparable, List.copyOf(warnings));
    }

    /** 历史模拟：相邻快照组合收益率取负乘以期初价值，得到损失样本（正为损失）。 */
    private List<Double> historicalLosses(Portfolio portfolio, List<MarketSnapshot> ordered, double baseValue) {
        List<Double> losses = new ArrayList<>();
        for (int i = 1; i < ordered.size(); i++) {
            double prev = valuationEngine.value(portfolio, ordered.get(i - 1));
            double curr = valuationEngine.value(portfolio, ordered.get(i));
            double ret = curr / prev - 1.0;
            losses.add(-ret * baseValue);
        }
        return losses;
    }

    /**
     * 相关性调整的组合损失：l_i = 持仓 i 的独立损失（w_i * -shock_i），
     * 组合损失 = sign(Σl_i) * sqrt(|Σ_ij l_i l_j c_ij|)。确定性、可解释。
     */
    private double correlationAdjustedLoss(Portfolio portfolio, MarketSnapshot latest, ScenarioDefinition scenario) {
        CorrelationMatrix corr = scenario.correlation();
        List<String> ids = corr.assetIds();
        int n = ids.size();
        double[] standalone = new double[n];
        for (Position p : portfolio.positions()) {
            int idx = ids.indexOf(p.assetId());
            double w = p.quantity() * latest.prices().get(p.assetId());
            double shock = scenario.shocks().getOrDefault(p.assetId(), 0.0);
            standalone[idx] += w * -shock;
        }
        double quad = 0.0;
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            sum += standalone[i];
            for (int j = 0; j < n; j++) {
                quad += standalone[i] * standalone[j] * corr.matrix().get(i).get(j);
            }
        }
        double sign = sum < 0 ? -1.0 : 1.0;
        return sign * Math.sqrt(Math.abs(quad));
    }
}
