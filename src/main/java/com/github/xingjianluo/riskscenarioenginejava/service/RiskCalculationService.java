package com.github.xingjianluo.riskscenarioenginejava.service;

import com.github.xingjianluo.riskscenarioenginejava.engine.RiskStatistics;
import com.github.xingjianluo.riskscenarioenginejava.engine.ScenarioSimulator;
import com.github.xingjianluo.riskscenarioenginejava.engine.SimulationOutcome;
import com.github.xingjianluo.riskscenarioenginejava.model.MarketSnapshot;
import com.github.xingjianluo.riskscenarioenginejava.model.RejectCode;
import com.github.xingjianluo.riskscenarioenginejava.model.RiskResult;
import com.github.xingjianluo.riskscenarioenginejava.model.ScenarioType;
import com.github.xingjianluo.riskscenarioenginejava.model.TrialRequest;
import com.github.xingjianluo.riskscenarioenginejava.util.InputDigest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 风险计算：确定性、无外部依赖的纯计算过程。
 *
 * <ol>
 *   <li>输入校验（拒绝原因可区分，不静默跳过）；</li>
 *   <li>场景模拟得到损失样本；</li>
 *   <li>计算 VaR/ES（场景冲击为单样本时尾部指标不适用，记 0 并在 notes 标注）。</li>
 * </ol>
 *
 * <p>同一归一化输入必定得到同一结论（含指标与样本计数）。REJECTED 属于确定结论、
 * 不可重试；技术异常向上抛出，由执行层记为 FAILED 并按策略重试。</p>
 */
@Service
public class RiskCalculationService {

    private static final Logger LOG = Logger.getLogger(RiskCalculationService.class.getName());

    private final InputValidationService validationService;
    private final ScenarioSimulator scenarioSimulator;
    private final RiskStatistics riskStatistics;

    public RiskCalculationService(InputValidationService validationService,
                                  ScenarioSimulator scenarioSimulator,
                                  RiskStatistics riskStatistics) {
        this.validationService = validationService;
        this.scenarioSimulator = scenarioSimulator;
        this.riskStatistics = riskStatistics;
    }

    public RiskResult calculate(TrialRequest request) {
        return calculate(request, 1);
    }

    /**
     * @param attempt 当前执行尝试次数（技术失败重试时由执行层递增并透传）
     */
    public RiskResult calculate(TrialRequest request, int attempt) {
        String requestId = request == null || request.requestId() == null
                ? "(anonymous)" : request.requestId();
        LOG.info(() -> "[CALC-START] requestId=" + requestId
                + " 输入摘要: " + InputDigest.summary(
                request == null ? null : request.portfolio(),
                request == null ? null : request.snapshots(),
                request == null ? null : request.scenario()));

        ValidationOutcome validation = validationService.validate(request);
        if (!validation.accepted()) {
            LOG.info(() -> "[CALC-REJECT] requestId=" + requestId
                    + " 场景版本=" + safeVersion(request)
                    + " 拒绝原因=" + validation.rejectCode() + " - " + validation.rejectReason());
            return rejected(request, validation.rejectCode(), validation.rejectReason(), attempt);
        }

        ValidatedInput input = validation.input();
        String digest = InputDigest.digest(input.portfolio(), input.sortedSnapshots(),
                input.scenario());

        SimulationOutcome outcome;
        try {
            outcome = scenarioSimulator.simulate(input.portfolio(), input.sortedSnapshots(),
                    input.latestSnapshot(), input.scenario());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, e, () -> "[CALC-ERROR] requestId=" + requestId
                    + " 计算抛出技术异常（可重试）: " + e.getMessage());
            throw e;
        }

        if (!outcome.accepted()) {
            LOG.info(() -> "[CALC-REJECT] requestId=" + requestId
                    + " 场景版本=" + input.scenario().version()
                    + " 输入摘要=" + digest
                    + " 拒绝原因=" + outcome.rejectCode() + " - " + outcome.rejectReason());
            return rejected(request, outcome.rejectCode(), outcome.rejectReason(), attempt,
                    input, digest, outcome.notes());
        }

        double[] losses = outcome.lossSamples();
        double baseValue = valuationBase(input);
        RiskResult result;
        if (input.scenario().type() == ScenarioType.SCENARIO_SHOCK) {
            double stressedPnl = -losses[0];
            result = new RiskResult(requestId, input.portfolio().portfolioId(),
                    input.scenario().scenarioId(), input.scenario().version(),
                    com.github.xingjianluo.riskscenarioenginejava.model.ResultStatus.ACCEPTED,
                    null, null,
                    baseValue, stressedPnl, 0d, 0d, 0d, 0d,
                    losses.length, digest, outcome.notes(),
                    Instant.now().toEpochMilli(), attempt);
        } else {
            RiskStatistics.TailMetric m95 = riskStatistics.quantileAndEs(losses, 0.95);
            RiskStatistics.TailMetric m99 = riskStatistics.quantileAndEs(losses, 0.99);
            double expectedPnl = -mean(losses);
            List<String> notes = outcome.notes();
            result = new RiskResult(requestId, input.portfolio().portfolioId(),
                    input.scenario().scenarioId(), input.scenario().version(),
                    com.github.xingjianluo.riskscenarioenginejava.model.ResultStatus.ACCEPTED,
                    null, null,
                    baseValue, expectedPnl,
                    m95.varValue(), m95.esValue(), m99.varValue(), m99.esValue(),
                    losses.length, digest, notes,
                    Instant.now().toEpochMilli(), attempt);
        }

        LOG.info(() -> "[CALC-ACCEPT] requestId=" + requestId
                + " 场景版本=" + input.scenario().version()
                + " 输入摘要=" + digest
                + " 判定依据=" + String.join(" | ", outcome.notes())
                + " 样本计数=" + result.sampleCount()
                + " baseValue=" + fmt(result.baseValue())
                + " VaR95=" + fmt(result.var95()) + " ES95=" + fmt(result.es95())
                + " VaR99=" + fmt(result.var99()) + " ES99=" + fmt(result.es99())
                + " attempt=" + attempt);
        return result;
    }

    private double valuationBase(ValidatedInput input) {
        double value = 0.0;
        MarketSnapshot latest = input.latestSnapshot();
        for (var h : input.portfolio().holdings()) {
            value += h.quantity() * latest.prices().get(h.instrumentId());
        }
        return value;
    }

    private double mean(double[] values) {
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.length;
    }

    private RiskResult rejected(TrialRequest request, RejectCode code, String reason, int attempt) {
        String portfolioId = request != null && request.portfolio() != null
                ? request.portfolio().portfolioId() : null;
        String scenarioId = request != null && request.scenario() != null
                ? request.scenario().scenarioId() : null;
        String version = safeVersion(request);
        String requestId = request == null || request.requestId() == null
                ? "(anonymous)" : request.requestId();
        return new RiskResult(requestId, portfolioId, scenarioId, version,
                com.github.xingjianluo.riskscenarioenginejava.model.ResultStatus.REJECTED,
                code, reason, 0d, 0d, 0d, 0d, 0d, 0d, 0, null, List.of(),
                Instant.now().toEpochMilli(), attempt);
    }

    private RiskResult rejected(TrialRequest request, RejectCode code, String reason, int attempt,
                                ValidatedInput input, String digest, List<String> priorNotes) {
        String requestId = request.requestId() == null ? "(anonymous)" : request.requestId();
        return new RiskResult(requestId, input.portfolio().portfolioId(),
                input.scenario().scenarioId(), input.scenario().version(),
                com.github.xingjianluo.riskscenarioenginejava.model.ResultStatus.REJECTED,
                code, reason, 0d, 0d, 0d, 0d, 0d, 0d, 0, digest,
                priorNotes == null ? List.of() : priorNotes,
                Instant.now().toEpochMilli(), attempt);
    }

    private String safeVersion(TrialRequest request) {
        if (request == null || request.scenario() == null || request.scenario().version() == null) {
            return "(unknown)";
        }
        return request.scenario().version();
    }

    private String fmt(double v) {
        return String.format("%.4f", v);
    }
}
