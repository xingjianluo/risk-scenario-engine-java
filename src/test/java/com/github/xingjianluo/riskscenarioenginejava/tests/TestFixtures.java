package com.github.xingjianluo.riskscenarioenginejava.tests;

import com.github.xingjianluo.riskscenarioenginejava.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试夹具：构造确定性的组合、行情快照与场景，并打印输入摘要便于日志追溯。
 */
final class TestFixtures {

    private static final Logger log = LoggerFactory.getLogger(TestFixtures.class);

    private TestFixtures() {
    }

    static Portfolio portfolio() {
        return new Portfolio("PF-1", List.of(
                new Position("EQ-A", "A", 100),
                new Position("EQ-B", "B", 50)));
    }

    /** n 期快照：A 从 10 起每期 +1%，B 从 20 起每期 -0.5%，确定性。 */
    static List<MarketSnapshot> snapshots(int n) {
        List<MarketSnapshot> out = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < n; i++) {
            Map<String, Double> prices = new LinkedHashMap<>();
            prices.put("A", 10.0 * Math.pow(1.01, i));
            prices.put("B", 20.0 * Math.pow(0.995, i));
            out.add(new MarketSnapshot(d.plusDays(i), prices));
        }
        return out;
    }

    static ScenarioDefinition scenario(String version) {
        return new ScenarioDefinition("SC-1", version,
                Map.of("A", -0.10, "B", 0.05), null, 5, 1);
    }

    static ScenarioDefinition scenarioWithCorrelation(String version, List<List<Double>> matrix) {
        return new ScenarioDefinition("SC-CORR", version,
                Map.of("A", -0.10, "B", -0.05),
                new CorrelationMatrix(List.of("A", "B"), matrix), 5, 1);
    }

    static void logSummary(String caseName, Portfolio p, List<MarketSnapshot> snaps, ScenarioDefinition sc) {
        log.info("[{}] input summary: portfolio={} positions={} snapshots={} first={} last={} scenario={} version={} shocks={}",
                caseName, p.portfolioId(), p.positions().size(), snaps.size(),
                snaps.get(0).asOfDate(), snaps.get(snaps.size() - 1).asOfDate(),
                sc.scenarioId(), sc.version(), sc.shocks());
    }
}
