package com.github.xingjianluo.riskscenarioenginejava.tests;

import com.github.xingjianluo.riskscenarioenginejava.domain.*;
import com.github.xingjianluo.riskscenarioenginejava.engine.RiskMetricsCalculator;
import com.github.xingjianluo.riskscenarioenginejava.engine.ScenarioAnalysisService;
import com.github.xingjianluo.riskscenarioenginejava.engine.ValuationEngine;
import com.github.xingjianluo.riskscenarioenginejava.validation.DataValidationException;
import com.github.xingjianluo.riskscenarioenginejava.validation.InputValidator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 历史样本与尾部样本不足必须明确拒绝；版本不匹配拒绝、未锁定版本标注不可比。
 */
class TailSampleTest {

    private static final Logger log = LoggerFactory.getLogger(TailSampleTest.class);

    private final ScenarioAnalysisService service =
            new ScenarioAnalysisService(new InputValidator(), new ValuationEngine(), new RiskMetricsCalculator());

    @Test
    void insufficientHistoryRejected() {
        Portfolio p = TestFixtures.portfolio();
        List<MarketSnapshot> snaps = TestFixtures.snapshots(3); // 仅 2 个收益点
        ScenarioDefinition sc = new ScenarioDefinition("SC-1", "v1",
                java.util.Map.of("A", -0.1), null, 5, 1);
        TestFixtures.logSummary("insufficientHistoryRejected", p, snaps, sc);

        DataValidationException ex = assertThrows(DataValidationException.class,
                () -> service.analyze(p, snaps, sc, "v1"));
        log.info("[insufficientHistoryRejected] rejected reason={} detail={}", ex.reason(), ex.getMessage());
        assertEquals(RejectionReason.INSUFFICIENT_HISTORY, ex.reason());
    }

    @Test
    void insufficientTailSamplesRejected() {
        Portfolio p = TestFixtures.portfolio();
        List<MarketSnapshot> snaps = TestFixtures.snapshots(11); // 10 个收益点，99% 尾部仅 1 个样本
        ScenarioDefinition sc = new ScenarioDefinition("SC-1", "v1",
                java.util.Map.of("A", -0.1), null, 5, 3);
        TestFixtures.logSummary("insufficientTailSamplesRejected", p, snaps, sc);

        DataValidationException ex = assertThrows(DataValidationException.class,
                () -> service.analyze(p, snaps, sc, "v1"));
        log.info("[insufficientTailSamplesRejected] rejected reason={} detail={}", ex.reason(), ex.getMessage());
        assertEquals(RejectionReason.INSUFFICIENT_TAIL_SAMPLES, ex.reason());
    }

    @Test
    void sufficientTailSamplesProduceMetrics() {
        Portfolio p = TestFixtures.portfolio();
        List<MarketSnapshot> snaps = TestFixtures.snapshots(41); // 40 个收益点，99% 尾部 1 个样本
        ScenarioDefinition sc = new ScenarioDefinition("SC-1", "v1",
                java.util.Map.of("A", -0.1), null, 5, 1);
        RiskReport r = service.analyze(p, snaps, sc, "v1");
        log.info("[sufficientTailSamples] version={} samples={} mean={} var95={} es95={} var99={} es99={}",
                r.scenarioVersion(), r.historicalLosses().sampleCount(), r.historicalLosses().mean(),
                r.historicalLosses().var95(), r.historicalLosses().es95(),
                r.historicalLosses().var99(), r.historicalLosses().es99());
        assertEquals(40, r.historicalLosses().sampleCount());
        assertTrue(r.historicalLosses().es95() >= r.historicalLosses().var95());
        assertTrue(r.historicalLosses().var99() >= r.historicalLosses().var95());
    }

    @Test
    void scenarioVersionMismatchRejected() {
        Portfolio p = TestFixtures.portfolio();
        List<MarketSnapshot> snaps = TestFixtures.snapshots(8);
        ScenarioDefinition sc = TestFixtures.scenario("v2");
        TestFixtures.logSummary("scenarioVersionMismatchRejected", p, snaps, sc);

        DataValidationException ex = assertThrows(DataValidationException.class,
                () -> service.analyze(p, snaps, sc, "v1"));
        log.info("[scenarioVersionMismatchRejected] rejected reason={} detail={}", ex.reason(), ex.getMessage());
        assertEquals(RejectionReason.SCENARIO_VERSION_MISMATCH, ex.reason());
    }

    @Test
    void unpinnedVersionMarkedNotComparable() {
        Portfolio p = TestFixtures.portfolio();
        List<MarketSnapshot> snaps = TestFixtures.snapshots(8);
        ScenarioDefinition sc = TestFixtures.scenario("v2");
        RiskReport r = service.analyze(p, snaps, sc, null);
        log.info("[unpinnedVersion] comparable={} warnings={}", r.comparable(), r.warnings());
        assertFalse(r.comparable());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("not pinned")));
    }

    @Test
    void historicalResultReproducibleByVersion() {
        // 同一版本 + 同一输入 => 结论逐字段一致（可按版本复现）
        Portfolio p = TestFixtures.portfolio();
        List<MarketSnapshot> snaps = TestFixtures.snapshots(15);
        ScenarioDefinition sc = TestFixtures.scenario("v3");
        RiskReport r1 = service.analyze(p, snaps, sc, "v3");
        RiskReport r2 = service.analyze(p, snaps, sc, "v3");
        log.info("[reproducible] v{} run1==run2: {}", r1.scenarioVersion(), r1.equals(r2));
        assertEquals(r1, r2);
    }
}
