package com.github.xingjianluo.riskscenarioenginejava.tests;

import com.github.xingjianluo.riskscenarioenginejava.engine.MatrixValidator;
import com.github.xingjianluo.riskscenarioenginejava.engine.RiskStatistics;
import com.github.xingjianluo.riskscenarioenginejava.engine.ScenarioSimulator;
import com.github.xingjianluo.riskscenarioenginejava.engine.ValuationEngine;
import com.github.xingjianluo.riskscenarioenginejava.model.MarketSnapshot;
import com.github.xingjianluo.riskscenarioenginejava.model.Portfolio;
import com.github.xingjianluo.riskscenarioenginejava.model.RejectCode;
import com.github.xingjianluo.riskscenarioenginejava.model.RiskResult;
import com.github.xingjianluo.riskscenarioenginejava.model.ScenarioDefinition;
import com.github.xingjianluo.riskscenarioenginejava.model.TrialRequest;
import com.github.xingjianluo.riskscenarioenginejava.service.InputValidationService;
import com.github.xingjianluo.riskscenarioenginejava.service.RiskCalculationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 风险计算服务：确定性、可复现、拒绝可区分、跨版本不可比标注。 */
class RiskCalculationServiceTest {

    private static final Logger LOG = Logger.getLogger(RiskCalculationServiceTest.class.getName());

    private RiskCalculationService service;

    @BeforeEach
    void setUp() {
        service = new RiskCalculationService(
                new InputValidationService(),
                new ScenarioSimulator(new ValuationEngine(), new MatrixValidator()),
                new RiskStatistics());
    }

    private TrialRequest shockRequest(String requestId) {
        Portfolio portfolio = TestData.portfolio("PF-1", 100, -50);
        List<MarketSnapshot> snapshots = TestData.snapshots(5, 100, 20, 0.2, 0.05);
        ScenarioDefinition scenario = TestData.shockScenario("S1", TestData.V_SHOCK, -0.1, 0.05);
        return new TrialRequest(requestId, portfolio, snapshots, scenario, null, null);
    }

    private TrialRequest historicalRequest(String requestId, String version) {
        Portfolio portfolio = TestData.portfolio("PF-1", 100, 50);
        List<MarketSnapshot> snapshots = TestData.snapshots(5, 100, 20, 0.2, 0.05);
        ScenarioDefinition scenario = new ScenarioDefinition("H1", version,
                scenarioType(), null, null, null,
                TestData.historicalScenario("H1", version, 25).historicalReturns(), 0L, 0);
        return new TrialRequest(requestId, portfolio, snapshots, scenario, null, null);
    }

    private com.github.xingjianluo.riskscenarioenginejava.model.ScenarioType scenarioType() {
        return com.github.xingjianluo.riskscenarioenginejava.model.ScenarioType.HISTORICAL_SIMULATION;
    }

    @Test
    @DisplayName("场景冲击正常结论：状态ACCEPTED，指标确定且重复计算完全一致")
    void shockAcceptedAndDeterministic() {
        RiskResult r1 = service.calculate(shockRequest("R-1"));
        RiskResult r2 = service.calculate(shockRequest("R-1"));
        LOG.info("输入摘要: 组合PF-1 2持仓 5期快照, 场景版本=" + TestData.V_SHOCK
                + "; 判定依据: 确定性冲击单样本; 拒绝原因: 无; digest=" + r1.inputDigest());
        assertTrue(r1.status().name().equals("ACCEPTED"));
        assertNotNull(r1.inputDigest());
        assertEquals(16, r1.inputDigest().length());
        assertEquals(r1.inputDigest(), r2.inputDigest());
        assertEquals(r1.baseValue(), r2.baseValue(), 0d);
        assertEquals(r1.stressedPnl(), r2.stressedPnl(), 0d);
        assertEquals(1, r1.sampleCount());
        assertEquals(1, r1.attempt());
    }

    @Test
    @DisplayName("乱序输入与顺序输入产生相同摘要和指标（确定性归一化）")
    void shuffledInputSameDigest() {
        TrialRequest ordered = shockRequest("R-2");
        List<MarketSnapshot> reversed = new java.util.ArrayList<>(ordered.snapshots());
        java.util.Collections.reverse(reversed);
        TrialRequest shuffled = new TrialRequest("R-3", ordered.portfolio(), reversed,
                ordered.scenario(), null, null);
        RiskResult r1 = service.calculate(ordered);
        RiskResult r2 = service.calculate(shuffled);
        LOG.info("判定依据: 快照排序后参与摘要; digest顺序=" + r1.inputDigest()
                + " digest乱序=" + r2.inputDigest());
        assertEquals(r1.inputDigest(), r2.inputDigest());
        assertEquals(r1.stressedPnl(), r2.stressedPnl(), 0d);
        assertEquals(r1.baseValue(), r2.baseValue(), 0d);
        assertFalse(r2.notes().isEmpty(), "乱序处理必须在notes中留痕");
    }

    @Test
    @DisplayName("历史模拟正常结论：VaR/ES为正且样本计数=期数，重复计算一致")
    void historicalAccepted() {
        RiskResult r1 = service.calculate(historicalRequest("R-4", TestData.V_HIST));
        RiskResult r2 = service.calculate(historicalRequest("R-4", TestData.V_HIST));
        LOG.info("输入摘要: 历史25期, 场景版本=" + TestData.V_HIST
                + "; 判定依据: 历史模拟次序统计量; VaR95=" + r1.var95() + " ES95=" + r1.es95());
        assertEquals("ACCEPTED", r1.status().name());
        assertEquals(25, r1.sampleCount());
        assertTrue(r1.var95() >= 0);
        assertTrue(r1.es95() >= r1.var95(), "ES(条件尾部均值)不应小于切点VaR");
        assertEquals(r1.var95(), r2.var95(), 0d);
        assertEquals(r1.es99(), r2.es99(), 0d);
        assertEquals(r1.inputDigest(), r2.inputDigest());
    }

    @Test
    @DisplayName("非法输入得到REJECTED及可区分原因码，指标归零、样本计数为0")
    void rejectedResultDistinguishable() {
        Portfolio portfolio = TestData.portfolio("PF-1", 100, 50);
        List<MarketSnapshot> snapshots = List.of();
        ScenarioDefinition scenario = TestData.shockScenario("S1", TestData.V_SHOCK, -0.1, 0.0);
        RiskResult result = service.calculate(
                new TrialRequest("R-5", portfolio, snapshots, scenario, null, null));
        LOG.info("场景版本=" + TestData.V_SHOCK + "; 判定依据: 快照为空; 拒绝原因="
                + result.rejectCode());
        assertEquals("REJECTED", result.status().name());
        assertEquals(RejectCode.SNAPSHOT_LIST_EMPTY, result.rejectCode());
        assertEquals(0, result.sampleCount());
        assertEquals(0d, result.var95(), 0d);
    }

    @Test
    @DisplayName("尾部样本不足：历史19期被明确拒绝 TAIL_SAMPLES_INSUFFICIENT")
    void insufficientTailSamplesRejected() {
        Portfolio portfolio = TestData.portfolio("PF-1", 100, 50);
        List<MarketSnapshot> snapshots = TestData.snapshots(5, 100, 20, 0.2, 0.05);
        ScenarioDefinition scenario = new ScenarioDefinition("H1", TestData.V_HIST,
                scenarioType(), null, null, null,
                TestData.historicalScenario("H1", TestData.V_HIST, 19).historicalReturns(), 0L, 0);
        RiskResult result = service.calculate(
                new TrialRequest("R-6", portfolio, snapshots, scenario, null, null));
        LOG.info("输入摘要: 历史19期, 场景版本=" + TestData.V_HIST
                + "; 判定依据: 尾部下限20; 拒绝原因=" + result.rejectCode());
        assertEquals("REJECTED", result.status().name());
        assertEquals(RejectCode.TAIL_SAMPLES_INSUFFICIENT, result.rejectCode());
    }

    @Test
    @DisplayName("场景版本变化：两期ACCEPTED结论标注不可比；同版本可比")
    void versionChangeMarkedIncomparable() {
        RiskResult v1 = service.calculate(historicalRequest("R-7", "2026-08-hist-v1"));
        RiskResult v2 = service.calculate(historicalRequest("R-8", "2026-09-hist-v1"));
        RiskResult v1Again = service.calculate(historicalRequest("R-9", "2026-08-hist-v1"));
        LOG.info("判定依据: scenarioId相同但版本2026-08->2026-09; 输入摘要不同("
                + v1.inputDigest() + "/" + v2.inputDigest() + ")");
        RiskResult.Comparability changed = v1.comparabilityWith(v2);
        RiskResult.Comparability same = v1.comparabilityWith(v1Again);
        assertFalse(changed.comparable());
        assertTrue(changed.reason().contains("场景版本变化"));
        assertTrue(same.comparable());
    }

    @Test
    @DisplayName("REJECTED结论与ACCEPTED结论不可比")
    void rejectedNotComparable() {
        RiskResult accepted = service.calculate(shockRequest("R-10"));
        RiskResult rejected = service.calculate(new TrialRequest("R-11",
                TestData.portfolio("PF", 1, 1), List.of(),
                TestData.shockScenario("S1", TestData.V_SHOCK, -0.1, -0.1), null, null));
        RiskResult.Comparability c = accepted.comparabilityWith(rejected);
        LOG.info("判定依据: 含REJECTED结论; 拒绝原因=" + rejected.rejectCode());
        assertFalse(c.comparable());
    }
}
