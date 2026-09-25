package com.github.xingjianluo.riskscenarioenginejava.util;

import com.github.xingjianluo.riskscenarioenginejava.model.Holding;
import com.github.xingjianluo.riskscenarioenginejava.model.MarketSnapshot;
import com.github.xingjianluo.riskscenarioenginejava.model.Portfolio;
import com.github.xingjianluo.riskscenarioenginejava.model.ScenarioDefinition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 试算输入的确定性摘要（SHA-256，截断 16 位十六进制）。
 *
 * <p>摘要对快照顺序不敏感（按日期排序后参与摘要），因此同一组乱序提交产生同一摘要，
 * 可作为幂等键；重复快照/重复持仓属于非法输入，不会进入摘要。</p>
 */
public final class InputDigest {

    private InputDigest() {
    }

    /** 对已归一化输入计算摘要：sortedSnapshots 必须为按日期升序的快照。 */
    public static String digest(Portfolio portfolio,
                                List<MarketSnapshot> sortedSnapshots,
                                ScenarioDefinition scenario) {
        StringBuilder sb = new StringBuilder();
        sb.append("P|").append(nullToEmpty(portfolio.portfolioId())).append('|');
        for (Holding h : portfolio.holdings()) {
            sb.append(h.instrumentId()).append('=').append(h.quantity()).append(';');
        }
        sb.append("||S|");
        for (MarketSnapshot snapshot : sortedSnapshots) {
            sb.append(snapshot.snapshotDate()).append('=');
            Map<String, Double> ordered = new TreeMap<>(snapshot.prices());
            ordered.forEach((k, v) -> sb.append(k).append(':').append(v).append(','));
            sb.append(';');
        }
        sb.append("||C|")
                .append(nullToEmpty(scenario.scenarioId())).append('|')
                .append(nullToEmpty(scenario.version())).append('|')
                .append(scenario.type()).append('|');
        if (scenario.shocks() != null) {
            new TreeMap<>(scenario.shocks())
                    .forEach((k, v) -> sb.append(k).append(':').append(v).append(','));
        }
        sb.append('|');
        if (scenario.correlationInstruments() != null) {
            sb.append(String.join(",", scenario.correlationInstruments())).append('|');
        }
        if (scenario.correlation() != null) {
            for (double[] row : scenario.correlation()) {
                for (double v : row) {
                    sb.append(v).append(',');
                }
                sb.append(';');
            }
        }
        sb.append('|');
        if (scenario.historicalReturns() != null) {
            Map<String, List<Double>> ordered = new TreeMap<>(scenario.historicalReturns());
            ordered.forEach((k, list) -> {
                sb.append(k).append(':');
                for (Double v : list) {
                    sb.append(v).append(',');
                }
                sb.append(';');
            });
        }
        sb.append("|seed=").append(scenario.seed())
                .append("|n=").append(scenario.sampleCount());
        return sha256Short(sb.toString());
    }

    /** 生成便于日志阅读的输入摘要文本（不含矩阵全量，只含关键规模信息）。 */
    public static String summary(Portfolio portfolio,
                                 List<MarketSnapshot> snapshots,
                                 ScenarioDefinition scenario) {
        String pid = portfolio == null ? "null" : portfolio.portfolioId();
        int holdings = portfolio == null || portfolio.holdings() == null ? 0 : portfolio.holdings().size();
        int snaps = snapshots == null ? 0 : snapshots.size();
        String cid = scenario == null ? "null" : scenario.scenarioId();
        String ver = scenario == null ? "null" : scenario.version();
        String type = scenario == null ? "null" : String.valueOf(scenario.type());
        return "portfolio=" + pid + ", holdings=" + holdings
                + ", snapshots=" + snaps
                + ", scenario=" + cid + ", version=" + ver + ", type=" + type;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String sha256Short(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
