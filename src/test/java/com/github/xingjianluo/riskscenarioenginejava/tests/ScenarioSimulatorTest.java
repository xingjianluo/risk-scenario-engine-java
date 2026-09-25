package com.github.xingjianluo.riskscenarioenginejava.tests;

import com.github.xingjianluo.riskscenarioenginejava.engine.MatrixValidator;
import com.github.xingjianluo.riskscenarioenginejava.engine.ScenarioSimulator;
import com.github.xingjianluo.riskscenarioenginejava.engine.SimulationOutcome;
import com.github.xingjianluo.riskscenarioenginejava.engine.ValuationEngine;
import com.github.xingjianluo.riskscenarioenginejava.model.MarketSnapshot;
import com.github.xingjianluo.riskscenarioenginejava.model.Portfolio;
import com.github.xingjianluo.riskscenarioenginejava.model.RejectCode;
import com.github.xingjianluo.riskscenarioenginejava.model.ScenarioDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 场景模拟器：三类场景的正常口径与确定性拒绝边界。 */
class ScenarioSimulatorTest {

    private static final Logger LOG = Logger.getLogger(ScenarioSimulatorTest.class.getName());

    private ScenarioSimulator simulator;
    private Portfolio portfolio;
    private List<MarketSnapshot> snapshots;

    @BeforeEach
    void setUp() {
        simulator = new ScenarioSimulator(new ValuationEngine(), new MatrixValidator());
        portfolio = TestData.portfolio("PF-1", 100, -50);
        snapshots = TestData.snapshots(30, 100, 20, 0.2, 0.05);
    }

    @Test
    @DisplayName("场景冲击：PnL=100*100*(-0.1)+(-50)*20*(+0.05)=-1050，损失+1050")
    void deterministicShock() {
        ScenarioDefinition scenario = TestData.shockScenario(
                "SHOCK-1", TestData.V_SHOCK, -0.10, 0.05);
        SimulationOutcome outcome = simulator.simulate(portfolio, snapshots,
                snapshots.get(snapshots.size() - 1), scenario);
        LOG.info("输入摘要: holdings=[A:100,B:-50], 最新价[A=105.8,B=21.45], 冲击[A=-10%,B=+5%], 场景版本="
                + TestData.V_SHOCK + "; 判定依据: 线性PnL; 拒绝原因: 无");
        assertTrue(outcome.accepted());
        assertEquals(1, outcome.lossSamples().length);
        // A: 100*105.8*-0.10=-1058; B: -50*21.45*0.05=-53.625
        assertEquals(1058.0 + 53.625, outcome.lossSamples()[0], 1e-8);
    }

    @Test
    @DisplayName("相关性调整：同种子两次模拟损失序列完全一致（可复现）")
    void correlationSeedReproducible() {
        double[][] corr = {{1, 0.6}, {0.6, 1}};
        ScenarioDefinition s1 = TestData.correlationScenario("CORR-1", TestData.V_CORR, corr, 200, 42L);
        ScenarioDefinition s2 = TestData.correlationScenario("CORR-1", TestData.V_CORR, corr, 200, 42L);
        SimulationOutcome o1 = simulator.simulate(portfolio, snapshots,
                snapshots.get(snapshots.size() - 1), s1);
        SimulationOutcome o2 = simulator.simulate(portfolio, snapshots,
                snapshots.get(snapshots.size() - 1), s2);
        LOG.info("输入摘要: 相关阵0.6, 抽样200, 种子42; 场景版本=" + TestData.V_CORR
                + "; 判定依据: 同种子->同Random序列; 拒绝原因: 无");
        assertTrue(o1.accepted() && o2.accepted());
        assertEquals(200, o1.lossSamples().length);
        for (int i = 0; i < 200; i++) {
            assertEquals(o1.lossSamples()[i], o2.lossSamples()[i], 0d,
                    "相同种子必须产生完全相同的第 " + i + " 个样本");
        }
    }

    @Test
    @DisplayName("相关性调整：抽样次数不足20 -> TAIL_SAMPLES_INSUFFICIENT")
    void correlationTooFewSamplesRejected() {
        ScenarioDefinition scenario = TestData.correlationScenario(
                "CORR-2", TestData.V_CORR, new double[][]{{1, 0}, {0, 1}}, 19, 1L);
        SimulationOutcome outcome = simulator.simulate(portfolio, snapshots,
                snapshots.get(snapshots.size() - 1), scenario);
        LOG.info("输入摘要: 抽样次数19, 场景版本=" + TestData.V_CORR
                + "; 判定依据: 尾部样本下限20; 拒绝原因=" + outcome.rejectCode());
        assertFalse(outcome.accepted());
        assertEquals(RejectCode.TAIL_SAMPLES_INSUFFICIENT, outcome.rejectCode());
    }

    @Test
    @DisplayName("相关性调整：非PSD矩阵 -> CORRELATION_NOT_PSD")
    void correlationNonPsdRejected() {
        ScenarioDefinition scenario = TestData.correlationScenario(
                "CORR-3", TestData.V_CORR, new double[][]{{1, 1.2}, {1.2, 1}}, 200, 1L);
        SimulationOutcome outcome = simulator.simulate(portfolio, snapshots,
                snapshots.get(snapshots.size() - 1), scenario);
        LOG.info("输入摘要: 相关系数1.2, 场景版本=" + TestData.V_CORR
                + "; 判定依据: Cholesky失败; 拒绝原因=" + outcome.rejectCode());
        assertFalse(outcome.accepted());
        assertEquals(RejectCode.CORRELATION_NOT_PSD, outcome.rejectCode());
    }

    @Test
    @DisplayName("相关性调整：快照不足3期无法估波动率 -> VOLATILITY_SAMPLES_INSUFFICIENT")
    void correlationTooFewSnapshotsRejected() {
        List<MarketSnapshot> one = snapshots.subList(0, 2);
        ScenarioDefinition scenario = TestData.correlationScenario(
                "CORR-4", TestData.V_CORR, new double[][]{{1, 0}, {0, 1}}, 200, 1L);
        SimulationOutcome outcome = simulator.simulate(portfolio, one, one.get(1), scenario);
        LOG.info("输入摘要: 仅2期快照; 判定依据: 对数收益率对<2; 拒绝原因=" + outcome.rejectCode());
        assertFalse(outcome.accepted());
        assertEquals(RejectCode.VOLATILITY_SAMPLES_INSUFFICIENT, outcome.rejectCode());
    }

    @Test
    @DisplayName("历史模拟：样本数=期数，尾部指标可计算且结果确定")
    void historicalSimulationDeterministic() {
        ScenarioDefinition scenario = TestData.historicalScenario("HIST-1", TestData.V_HIST, 25);
        SimulationOutcome o1 = simulator.simulate(portfolio, snapshots,
                snapshots.get(snapshots.size() - 1), scenario);
        SimulationOutcome o2 = simulator.simulate(portfolio, snapshots,
                snapshots.get(snapshots.size() - 1), scenario);
        LOG.info("输入摘要: 25期历史收益率, 场景版本=" + TestData.V_HIST
                + "; 判定依据: 逐期线性重估; 拒绝原因: 无");
        assertTrue(o1.accepted());
        assertEquals(25, o1.lossSamples().length);
        for (int i = 0; i < 25; i++) {
            assertEquals(o1.lossSamples()[i], o2.lossSamples()[i], 0d);
        }
    }

    @Test
    @DisplayName("历史模拟：19期 -> TAIL_SAMPLES_INSUFFICIENT")
    void historicalTooFewSamplesRejected() {
        ScenarioDefinition scenario = TestData.historicalScenario("HIST-2", TestData.V_HIST, 19);
        SimulationOutcome outcome = simulator.simulate(portfolio, snapshots,
                snapshots.get(snapshots.size() - 1), scenario);
        LOG.info("输入摘要: 19期历史收益率, 场景版本=" + TestData.V_HIST
                + "; 判定依据: 尾部样本下限20; 拒绝原因=" + outcome.rejectCode());
        assertFalse(outcome.accepted());
        assertEquals(RejectCode.TAIL_SAMPLES_INSUFFICIENT, outcome.rejectCode());
    }

    @Test
    @DisplayName("历史模拟：缺少持仓标的B的序列 -> HISTORICAL_INSTRUMENT_MISSING")
    void historicalMissingInstrumentRejected() {
        ScenarioDefinition scenario = new ScenarioDefinition("HIST-3", TestData.V_HIST,
                com.github.xingjianluo.riskscenarioenginejava.model.ScenarioType.HISTORICAL_SIMULATION,
                null, null, null,
                java.util.Map.of("A", List.of(0.01, 0.02)), 0L, 0);
        // 2期数据本身也不足，但先检查标的覆盖：校验顺序为 标的缺失 -> 长度 -> 数量
        SimulationOutcome outcome = simulator.simulate(portfolio, snapshots,
                snapshots.get(snapshots.size() - 1), scenario);
        LOG.info("输入摘要: 历史序列仅含A, 组合持有[A,B]; 判定依据: 标的覆盖检查; 拒绝原因="
                + outcome.rejectCode());
        assertFalse(outcome.accepted());
        assertEquals(RejectCode.HISTORICAL_INSTRUMENT_MISSING, outcome.rejectCode());
    }
}
