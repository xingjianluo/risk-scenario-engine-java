package com.github.xingjianluo.riskscenarioenginejava.tests;

import com.github.xingjianluo.riskscenarioenginejava.model.Holding;
import com.github.xingjianluo.riskscenarioenginejava.model.MarketSnapshot;
import com.github.xingjianluo.riskscenarioenginejava.model.Portfolio;
import com.github.xingjianluo.riskscenarioenginejava.model.ScenarioDefinition;
import com.github.xingjianluo.riskscenarioenginejava.model.ScenarioType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 测试数据构造工厂：集中生成合法/非法组合、快照与场景。 */
final class TestData {

    static final String V_SHOCK = "2026-09-shock-v1";
    static final String V_CORR = "2026-09-corr-v1";
    static final String V_HIST = "2026-09-hist-v1";

    private TestData() {
    }

    static Portfolio portfolio(String id, double qtyA, double qtyB) {
        return new Portfolio(id, List.of(
                new Holding("A", qtyA),
                new Holding("B", qtyB)));
    }

    static Portfolio portfolioOne(String id, String instrument, double qty) {
        return new Portfolio(id, List.of(new Holding(instrument, qty)));
    }

    /** n 期逐日快照，A/B 价格按给定初始价与日变动生成。 */
    static List<MarketSnapshot> snapshots(int n, double startA, double startB,
                                          double driftA, double driftB) {
        List<MarketSnapshot> list = new ArrayList<>();
        LocalDate base = LocalDate.of(2026, 9, 1);
        for (int i = 0; i < n; i++) {
            Map<String, Double> prices = new HashMap<>();
            prices.put("A", startA + driftA * i);
            prices.put("B", startB + driftB * i);
            list.add(new MarketSnapshot(base.plusDays(i), prices));
        }
        return list;
    }

    static ScenarioDefinition shockScenario(String id, String version,
                                            double shockA, double shockB) {
        return new ScenarioDefinition(id, version, ScenarioType.SCENARIO_SHOCK,
                Map.of("A", shockA, "B", shockB),
                null, null, null, 0L, 0);
    }

    static ScenarioDefinition correlationScenario(String id, String version,
                                                  double[][] corr, int samples, long seed) {
        return new ScenarioDefinition(id, version, ScenarioType.CORRELATION_ADJUST,
                null, List.of("A", "B"), corr, null, seed, samples);
    }

    static ScenarioDefinition historicalScenario(String id, String version,
                                                 List<Double> returnsA,
                                                 List<Double> returnsB) {
        Map<String, List<Double>> data = new HashMap<>();
        data.put("A", returnsA);
        data.put("B", returnsB);
        return new ScenarioDefinition(id, version, ScenarioType.HISTORICAL_SIMULATION,
                null, null, null, data, 0L, 0);
    }

    /** 生成 n 期历史收益率：A 小幅波动，B 周期性下跌以便存在尾部损失。 */
    static ScenarioDefinition historicalScenario(String id, String version, int n) {
        List<Double> a = new ArrayList<>();
        List<Double> b = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            a.add((i % 5 - 2) * 0.002);
            b.add(i % 7 == 0 ? -0.05 : 0.01);
        }
        return historicalScenario(id, version, a, b);
    }
}
