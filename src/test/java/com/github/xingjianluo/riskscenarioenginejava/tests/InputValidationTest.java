package com.github.xingjianluo.riskscenarioenginejava.tests;

import com.github.xingjianluo.riskscenarioenginejava.domain.*;
import com.github.xingjianluo.riskscenarioenginejava.engine.ScenarioAnalysisService;
import com.github.xingjianluo.riskscenarioenginejava.engine.RiskMetricsCalculator;
import com.github.xingjianluo.riskscenarioenginejava.engine.ValuationEngine;
import com.github.xingjianluo.riskscenarioenginejava.validation.DataValidationException;
import com.github.xingjianluo.riskscenarioenginejava.validation.InputValidator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据缺失、乱序、重复快照等边界：拒绝原因可区分，乱序归一化结果确定。
 */
class InputValidationTest {

    private static final Logger log = LoggerFactory.getLogger(InputValidationTest.class);

    private final InputValidator validator = new InputValidator();
    private final ScenarioAnalysisService service =
            new ScenarioAnalysisService(validator, new ValuationEngine(), new RiskMetricsCalculator());

    @Test
    void missingPriceFieldRejected() {
        Portfolio p = TestFixtures.portfolio();
        List<MarketSnapshot> snaps = new ArrayList<>(TestFixtures.snapshots(8));
        // 抹掉最后一期 B 的价格 -> 缺失字段不得静默跳过
        snaps.set(7, new MarketSnapshot(snaps.get(7).asOfDate(), Map.of("A", 10.7)));
        ScenarioDefinition sc = TestFixtures.scenario("v1");
        TestFixtures.logSummary("missingPriceFieldRejected", p, snaps, sc);

        DataValidationException ex = assertThrows(DataValidationException.class,
                () -> service.analyze(p, snaps, sc, "v1"));
        log.info("[missingPriceFieldRejected] rejected reason={} detail={}", ex.reason(), ex.getMessage());
        assertEquals(RejectionReason.UNKNOWN_ASSET, ex.reason());
    }

    @Test
    void duplicateSnapshotDateRejected() {
        Portfolio p = TestFixtures.portfolio();
        List<MarketSnapshot> snaps = new ArrayList<>(TestFixtures.snapshots(8));
        snaps.add(snaps.get(3)); // 重复日期
        ScenarioDefinition sc = TestFixtures.scenario("v1");
        TestFixtures.logSummary("duplicateSnapshotDateRejected", p, snaps, sc);

        DataValidationException ex = assertThrows(DataValidationException.class,
                () -> service.analyze(p, snaps, sc, "v1"));
        log.info("[duplicateSnapshotDateRejected] rejected reason={} detail={}", ex.reason(), ex.getMessage());
        assertEquals(RejectionReason.DUPLICATE_SNAPSHOT_DATE, ex.reason());
    }

    @Test
    void outOfOrderSnapshotsNormalizedDeterministically() {
        Portfolio p = TestFixtures.portfolio();
        List<MarketSnapshot> ordered = TestFixtures.snapshots(10);
        List<MarketSnapshot> shuffled = new ArrayList<>(ordered);
        Collections.reverse(shuffled); // 完全逆序输入
        ScenarioDefinition sc = TestFixtures.scenario("v1");
        TestFixtures.logSummary("outOfOrderSnapshotsNormalizedDeterministically", p, shuffled, sc);

        RiskReport a = service.analyze(p, shuffled, sc, "v1");
        RiskReport b = service.analyze(p, ordered, sc, "v1");
        log.info("[outOfOrder] shuffled result: base={} loss={} es95={} warnings={}",
                a.baseValue(), a.scenarioLoss(), a.historicalLosses().es95(), a.warnings());
        log.info("[outOfOrder] ordered  result: base={} loss={} es95={} warnings={}",
                b.baseValue(), b.scenarioLoss(), b.historicalLosses().es95(), b.warnings());

        assertEquals(b.baseValue(), a.baseValue(), 1e-9);
        assertEquals(b.historicalLosses().sortedLosses(), a.historicalLosses().sortedLosses());
        assertTrue(a.warnings().stream().anyMatch(w -> w.contains("out of chronological order")),
                "乱序输入必须留下可解释告警");
    }

    @Test
    void repeatedComputationDeterministic() {
        Portfolio p = TestFixtures.portfolio();
        List<MarketSnapshot> snaps = TestFixtures.snapshots(12);
        ScenarioDefinition sc = TestFixtures.scenario("v1");
        RiskReport r1 = service.analyze(p, snaps, sc, "v1");
        RiskReport r2 = service.analyze(p, snaps, sc, "v1");
        log.info("[deterministic] run1 es95={} run2 es95={}",
                r1.historicalLosses().es95(), r2.historicalLosses().es95());
        assertEquals(r1, r2);
    }

    @Test
    void nonPositivePriceRejected() {
        List<MarketSnapshot> snaps = List.of(
                new MarketSnapshot(LocalDate.of(2026, 1, 1), Map.of("A", 10.0, "B", 0.0)));
        DataValidationException ex = assertThrows(DataValidationException.class,
                () -> validator.validateAndNormalizeSnapshots(snaps, new ArrayList<>()));
        log.info("[nonPositivePriceRejected] rejected reason={} detail={}", ex.reason(), ex.getMessage());
        assertEquals(RejectionReason.NON_POSITIVE_PRICE, ex.reason());
    }

    @Test
    void emptyPortfolioRejected() {
        DataValidationException ex = assertThrows(DataValidationException.class,
                () -> validator.validatePortfolio(new Portfolio("PF-X", List.of())));
        log.info("[emptyPortfolioRejected] rejected reason={} detail={}", ex.reason(), ex.getMessage());
        assertEquals(RejectionReason.EMPTY_PORTFOLIO, ex.reason());
    }

    @Test
    void shockOnUnknownAssetRejected() {
        Portfolio p = TestFixtures.portfolio();
        ScenarioDefinition sc = new ScenarioDefinition("SC-X", "v1",
                Map.of("ZZZ", -0.5), null, 0, 1);
        DataValidationException ex = assertThrows(DataValidationException.class,
                () -> service.analyze(p, TestFixtures.snapshots(3), sc, "v1"));
        log.info("[shockOnUnknownAssetRejected] rejected reason={} detail={}", ex.reason(), ex.getMessage());
        assertEquals(RejectionReason.UNKNOWN_ASSET, ex.reason());
    }
}
