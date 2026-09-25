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
 * 非法相关性矩阵必须明确拒绝：非对称、非单位对角、越界、非半正定。
 */
class CorrelationMatrixTest {

    private static final Logger log = LoggerFactory.getLogger(CorrelationMatrixTest.class);

    private final ScenarioAnalysisService service =
            new ScenarioAnalysisService(new InputValidator(), new ValuationEngine(), new RiskMetricsCalculator());

    private void expectInvalid(String caseName, List<List<Double>> matrix) {
        ScenarioDefinition sc = TestFixtures.scenarioWithCorrelation("v1", matrix);
        TestFixtures.logSummary(caseName, TestFixtures.portfolio(), TestFixtures.snapshots(8), sc);
        DataValidationException ex = assertThrows(DataValidationException.class,
                () -> service.analyze(TestFixtures.portfolio(), TestFixtures.snapshots(8), sc, "v1"));
        log.info("[{}] rejected reason={} detail={}", caseName, ex.reason(), ex.getMessage());
        assertEquals(RejectionReason.INVALID_CORRELATION_MATRIX, ex.reason());
    }

    @Test
    void asymmetricMatrixRejected() {
        expectInvalid("asymmetric", List.of(
                List.of(1.0, 0.3),
                List.of(0.8, 1.0)));
    }

    @Test
    void nonUnitDiagonalRejected() {
        expectInvalid("nonUnitDiagonal", List.of(
                List.of(0.9, 0.3),
                List.of(0.3, 1.0)));
    }

    @Test
    void outOfRangeCorrelationRejected() {
        expectInvalid("outOfRange", List.of(
                List.of(1.0, 1.5),
                List.of(1.5, 1.0)));
    }

    @Test
    void nonPositiveSemidefiniteRejected() {
        // 经典非 PSD：两资产都与第三资产高度正相关但彼此负相关
        ScenarioDefinition sc = new ScenarioDefinition("SC-PSD", "v1",
                java.util.Map.of("A", -0.1),
                new CorrelationMatrix(List.of("A", "B"), List.of(
                        List.of(1.0, -0.999),
                        List.of(-0.999, 1.0))), 0, 1);
        // 2x2 合法；构造 3 资产非 PSD 需组合含 3 资产，改用直接校验器路径
        new InputValidator().validateCorrelationMatrix(sc.correlation()); // 不应抛异常
        log.info("[nonPSD-2x2-ok] 2x2 matrix is PSD as expected");

        CorrelationMatrix bad = new CorrelationMatrix(List.of("X", "Y", "Z"), List.of(
                List.of(1.0, 0.9, 0.9),
                List.of(0.9, 1.0, -0.9),
                List.of(0.9, -0.9, 1.0)));
        DataValidationException ex = assertThrows(DataValidationException.class,
                () -> new InputValidator().validateCorrelationMatrix(bad));
        log.info("[nonPSD] rejected reason={} detail={}", ex.reason(), ex.getMessage());
        assertEquals(RejectionReason.INVALID_CORRELATION_MATRIX, ex.reason());
    }

    @Test
    void validCorrelationAdjustsScenarioLoss() {
        // 完全正相关时，相关性调整损失 = 各资产独立损失之和（同向冲击）
        Portfolio p = TestFixtures.portfolio();
        List<MarketSnapshot> snaps = TestFixtures.snapshots(8);
        ScenarioDefinition corr = TestFixtures.scenarioWithCorrelation("v1", List.of(
                List.of(1.0, 1.0),
                List.of(1.0, 1.0)));
        ScenarioDefinition noCorr = new ScenarioDefinition("SC-CORR", "v1",
                corr.shocks(), null, 5, 1);
        TestFixtures.logSummary("validCorrelationAdjustsScenarioLoss", p, snaps, corr);

        RiskReport withCorr = service.analyze(p, snaps, corr, "v1");
        RiskReport withoutCorr = service.analyze(p, snaps, noCorr, "v1");
        log.info("[validCorrelation] withCorr loss={} withoutCorr loss={} warnings={}",
                withCorr.scenarioLoss(), withoutCorr.scenarioLoss(), withCorr.warnings());

        // corr=1 时 sqrt(quad) = |Σl_i|，与未调整口径一致
        assertEquals(withoutCorr.scenarioLoss(), withCorr.scenarioLoss(), 1e-6);
        assertTrue(withCorr.warnings().stream().anyMatch(w -> w.contains("correlation")));
    }
}
