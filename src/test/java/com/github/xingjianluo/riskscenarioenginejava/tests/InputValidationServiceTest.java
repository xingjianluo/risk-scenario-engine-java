package com.github.xingjianluo.riskscenarioenginejava.tests;

import com.github.xingjianluo.riskscenarioenginejava.model.Holding;
import com.github.xingjianluo.riskscenarioenginejava.model.MarketSnapshot;
import com.github.xingjianluo.riskscenarioenginejava.model.Portfolio;
import com.github.xingjianluo.riskscenarioenginejava.model.RejectCode;
import com.github.xingjianluo.riskscenarioenginejava.model.ScenarioDefinition;
import com.github.xingjianluo.riskscenarioenginejava.model.TrialRequest;
import com.github.xingjianluo.riskscenarioenginejava.service.InputValidationService;
import com.github.xingjianluo.riskscenarioenginejava.service.ValidatedInput;
import com.github.xingjianluo.riskscenarioenginejava.service.ValidationOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 输入加载与校验：缺失/非法/乱序/重复输入的可区分拒绝与确定性归一化。 */
class InputValidationServiceTest {

    private static final Logger LOG = Logger.getLogger(InputValidationServiceTest.class.getName());

    private InputValidationService validator;

    @BeforeEach
    void setUp() {
        validator = new InputValidationService();
    }

    private TrialRequest validRequest() {
        Portfolio portfolio = TestData.portfolio("PF-1", 100, -50);
        List<MarketSnapshot> snapshots = TestData.snapshots(5, 100, 20, 0.2, 0.05);
        ScenarioDefinition scenario = TestData.shockScenario("S1", TestData.V_SHOCK, -0.1, 0.0);
        return new TrialRequest("R-1", portfolio, snapshots, scenario, null, null);
    }

    @Test
    @DisplayName("合法输入通过校验")
    void validInputAccepted() {
        ValidationOutcome outcome = validator.validate(validRequest());
        LOG.info("输入摘要: 合法组合2持仓+5期快照+冲击场景, 场景版本=" + TestData.V_SHOCK
                + "; 判定依据: 全部字段完整; 拒绝原因: 无");
        assertTrue(outcome.accepted());
    }

    @Test
    @DisplayName("组合缺失 -> PORTFOLIO_MISSING")
    void missingPortfolioRejected() {
        TrialRequest request = new TrialRequest("R", null, List.of(), null, null, null);
        RejectCode code = validator.validate(request).rejectCode();
        LOG.info("判定依据: portfolio=null; 拒绝原因=" + code);
        assertEquals(RejectCode.PORTFOLIO_MISSING, code);
    }

    @Test
    @DisplayName("组合ID空白 -> PORTFOLIO_ID_MISSING")
    void blankPortfolioIdRejected() {
        TrialRequest request = validRequest();
        request = new TrialRequest("R",
                new Portfolio("  ", request.portfolio().holdings()),
                request.snapshots(), request.scenario(), null, null);
        RejectCode code = validator.validate(request).rejectCode();
        LOG.info("判定依据: portfolioId为空白; 拒绝原因=" + code);
        assertEquals(RejectCode.PORTFOLIO_ID_MISSING, code);
    }

    @Test
    @DisplayName("空持仓 -> PORTFOLIO_EMPTY")
    void emptyHoldingsRejected() {
        TrialRequest request = validRequest();
        request = new TrialRequest("R", new Portfolio("PF", List.of()),
                request.snapshots(), request.scenario(), null, null);
        RejectCode code = validator.validate(request).rejectCode();
        LOG.info("判定依据: holdings为空; 拒绝原因=" + code);
        assertEquals(RejectCode.PORTFOLIO_EMPTY, code);
    }

    @Test
    @DisplayName("数量为0 -> HOLDING_QUANTITY_INVALID")
    void zeroQuantityRejected() {
        TrialRequest request = validRequest();
        request = new TrialRequest("R",
                new Portfolio("PF", List.of(new Holding("A", 0))),
                request.snapshots(), request.scenario(), null, null);
        RejectCode code = validator.validate(request).rejectCode();
        LOG.info("判定依据: A数量=0; 拒绝原因=" + code);
        assertEquals(RejectCode.HOLDING_QUANTITY_INVALID, code);
    }

    @Test
    @DisplayName("重复持仓标的 -> DUPLICATE_HOLDING")
    void duplicateHoldingRejected() {
        TrialRequest request = validRequest();
        request = new TrialRequest("R",
                new Portfolio("PF", List.of(new Holding("A", 1), new Holding("A", 2))),
                request.snapshots(), request.scenario(), null, null);
        RejectCode code = validator.validate(request).rejectCode();
        LOG.info("判定依据: A出现两次; 拒绝原因=" + code);
        assertEquals(RejectCode.DUPLICATE_HOLDING, code);
    }

    @Test
    @DisplayName("快照列表为空 -> SNAPSHOT_LIST_EMPTY")
    void noSnapshotsRejected() {
        TrialRequest request = validRequest();
        request = new TrialRequest("R", request.portfolio(), List.of(),
                request.scenario(), null, null);
        RejectCode code = validator.validate(request).rejectCode();
        LOG.info("判定依据: snapshots为空; 拒绝原因=" + code);
        assertEquals(RejectCode.SNAPSHOT_LIST_EMPTY, code);
    }

    @Test
    @DisplayName("重复日期快照 -> DUPLICATE_SNAPSHOT，不得静默跳过")
    void duplicateSnapshotRejected() {
        TrialRequest request = validRequest();
        List<MarketSnapshot> dup = new ArrayList<>(request.snapshots());
        dup.add(new MarketSnapshot(dup.get(0).snapshotDate(), Map.of("A", 1d, "B", 1d)));
        request = new TrialRequest("R", request.portfolio(), dup,
                request.scenario(), null, null);
        RejectCode code = validator.validate(request).rejectCode();
        LOG.info("判定依据: 存在相同日期快照; 拒绝原因=" + code);
        assertEquals(RejectCode.DUPLICATE_SNAPSHOT, code);
    }

    @Test
    @DisplayName("乱序快照被按日期重排，并在notes中标注；最新期取最大日期")
    void outOfOrderSnapshotsNormalized() {
        TrialRequest request = validRequest();
        List<MarketSnapshot> shuffled = new ArrayList<>(request.snapshots());
        java.util.Collections.reverse(shuffled);
        request = new TrialRequest("R", request.portfolio(), shuffled,
                request.scenario(), null, null);
        ValidationOutcome outcome = validator.validate(request);
        LOG.info("输入摘要: 5期快照逆序提交, 场景版本=" + TestData.V_SHOCK
                + "; 判定依据: 按日期升序归一化并标注; 拒绝原因: 无");
        assertTrue(outcome.accepted());
        ValidatedInput input = outcome.input();
        for (int i = 1; i < input.sortedSnapshots().size(); i++) {
            assertFalse(input.sortedSnapshots().get(i - 1).snapshotDate()
                    .isAfter(input.sortedSnapshots().get(i).snapshotDate()));
        }
        assertEquals(LocalDate.of(2026, 9, 5), input.latestSnapshot().snapshotDate());
        assertFalse(input.notes().isEmpty());
    }

    @Test
    @DisplayName("最新快照缺少持仓标的价格 -> MISSING_PRICE_FOR_INSTRUMENT")
    void missingLatestPriceRejected() {
        TrialRequest request = validRequest();
        List<MarketSnapshot> snaps = request.snapshots();
        List<MarketSnapshot> changed = new ArrayList<>(snaps);
        int last = changed.size() - 1;
        changed.set(last, new MarketSnapshot(changed.get(last).snapshotDate(), Map.of("A", 100d)));
        request = new TrialRequest("R", request.portfolio(), changed,
                request.scenario(), null, null);
        RejectCode code = validator.validate(request).rejectCode();
        LOG.info("判定依据: 最新快照无B价格; 拒绝原因=" + code);
        assertEquals(RejectCode.MISSING_PRICE_FOR_INSTRUMENT, code);
    }

    @Test
    @DisplayName("最新价为0或负 -> PRICE_NON_POSITIVE")
    void nonPositivePriceRejected() {
        TrialRequest request = validRequest();
        List<MarketSnapshot> changed = new ArrayList<>(request.snapshots());
        int last = changed.size() - 1;
        changed.set(last, new MarketSnapshot(changed.get(last).snapshotDate(),
                Map.of("A", 0d, "B", 20d)));
        request = new TrialRequest("R", request.portfolio(), changed,
                request.scenario(), null, null);
        RejectCode code = validator.validate(request).rejectCode();
        LOG.info("判定依据: A最新价=0; 拒绝原因=" + code);
        assertEquals(RejectCode.PRICE_NON_POSITIVE, code);
    }

    @Test
    @DisplayName("场景版本缺失 -> SCENARIO_VERSION_MISSING（无法跨期复现）")
    void missingVersionRejected() {
        TrialRequest request = validRequest();
        ScenarioDefinition noVersion = new ScenarioDefinition("S1", null,
                request.scenario().type(), request.scenario().shocks(), null, null, null, 0L, 0);
        request = new TrialRequest("R", request.portfolio(), request.snapshots(),
                noVersion, null, null);
        RejectCode code = validator.validate(request).rejectCode();
        LOG.info("判定依据: version=null; 拒绝原因=" + code);
        assertEquals(RejectCode.SCENARIO_VERSION_MISSING, code);
    }

    @Test
    @DisplayName("冲击引用未持有标的 -> SHOCK_REFERENCES_UNKNOWN_INSTRUMENT")
    void shockUnknownInstrumentRejected() {
        TrialRequest request = validRequest();
        ScenarioDefinition bad = new ScenarioDefinition("S1", TestData.V_SHOCK,
                request.scenario().type(), Map.of("A", -0.1, "C", -0.2),
                null, null, null, 0L, 0);
        request = new TrialRequest("R", request.portfolio(), request.snapshots(), bad, null, null);
        RejectCode code = validator.validate(request).rejectCode();
        LOG.info("判定依据: 冲击含未持有标的C; 拒绝原因=" + code);
        assertEquals(RejectCode.SHOCK_REFERENCES_UNKNOWN_INSTRUMENT, code);
    }

    @Test
    @DisplayName("历史序列长度不一致 -> HISTORICAL_SERIES_LENGTH_MISMATCH")
    void historicalLengthMismatchRejected() {
        Portfolio portfolio = TestData.portfolio("PF", 100, 50);
        List<MarketSnapshot> snapshots = TestData.snapshots(5, 100, 20, 0.2, 0.05);
        ScenarioDefinition scenario = TestData.historicalScenario("H", TestData.V_HIST,
                List.of(0.01, 0.02), List.of(0.01));
        RejectCode code = validator.validate(
                new TrialRequest("R", portfolio, snapshots, scenario, null, null)).rejectCode();
        LOG.info("输入摘要: A序列2期/B序列1期, 场景版本=" + TestData.V_HIST
                + "; 判定依据: 序列长度不一致; 拒绝原因=" + code);
        assertEquals(RejectCode.HISTORICAL_SERIES_LENGTH_MISMATCH, code);
    }

    @Test
    @DisplayName("蒙特卡洛抽样次数非正 -> MC_SAMPLE_COUNT_INVALID")
    void invalidSampleCountRejected() {
        Portfolio portfolio = TestData.portfolio("PF", 100, 50);
        List<MarketSnapshot> snapshots = TestData.snapshots(5, 100, 20, 0.2, 0.05);
        ScenarioDefinition scenario = TestData.correlationScenario(
                "C", TestData.V_CORR, new double[][]{{1, 0}, {0, 1}}, 0, 1L);
        RejectCode code = validator.validate(
                new TrialRequest("R", portfolio, snapshots, scenario, null, null)).rejectCode();
        LOG.info("判定依据: sampleCount=0; 拒绝原因=" + code);
        assertEquals(RejectCode.MC_SAMPLE_COUNT_INVALID, code);
    }
}
