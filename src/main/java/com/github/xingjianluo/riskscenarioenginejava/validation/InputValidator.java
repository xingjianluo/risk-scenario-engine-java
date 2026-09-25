package com.github.xingjianluo.riskscenarioenginejava.validation;

import com.github.xingjianluo.riskscenarioenginejava.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 输入校验：非法或不完整数据一律拒绝并给出可区分原因，不静默跳过。
 * 时序乱序的快照会被确定性地归一化（按日期升序）并记录告警；
 * 重复日期、缺失字段、非正价格、非法相关性矩阵直接拒绝。
 */
@Component
public class InputValidator {

    private static final Logger log = LoggerFactory.getLogger(InputValidator.class);

    public void validatePortfolio(Portfolio portfolio) {
        if (portfolio == null || portfolio.portfolioId() == null || portfolio.portfolioId().isBlank()) {
            throw reject(RejectionReason.MISSING_FIELD, "portfolioId is required");
        }
        if (portfolio.positions() == null || portfolio.positions().isEmpty()) {
            throw reject(RejectionReason.EMPTY_PORTFOLIO, "portfolio " + portfolio.portfolioId() + " has no positions");
        }
        Set<String> instruments = new HashSet<>();
        for (Position p : portfolio.positions()) {
            if (p == null || p.instrumentId() == null || p.instrumentId().isBlank()
                    || p.assetId() == null || p.assetId().isBlank()) {
                throw reject(RejectionReason.MISSING_FIELD,
                        "position in portfolio " + portfolio.portfolioId() + " misses instrumentId/assetId");
            }
            if (!instruments.add(p.instrumentId())) {
                throw reject(RejectionReason.MISSING_FIELD,
                        "duplicate instrumentId " + p.instrumentId() + " in portfolio " + portfolio.portfolioId());
            }
            if (!Double.isFinite(p.quantity()) || p.quantity() == 0.0) {
                throw reject(RejectionReason.MISSING_FIELD,
                        "position " + p.instrumentId() + " has invalid quantity " + p.quantity());
            }
        }
    }

    /** 返回按日期升序排列的快照；乱序输入归一化但记录告警，重复日期拒绝。 */
    public List<MarketSnapshot> validateAndNormalizeSnapshots(List<MarketSnapshot> snapshots, List<String> warnings) {
        if (snapshots == null || snapshots.isEmpty()) {
            throw reject(RejectionReason.MISSING_FIELD, "at least one market snapshot is required");
        }
        Set<java.time.LocalDate> seen = new HashSet<>();
        boolean outOfOrder = false;
        java.time.LocalDate prev = null;
        for (MarketSnapshot s : snapshots) {
            if (s == null || s.asOfDate() == null || s.prices() == null || s.prices().isEmpty()) {
                throw reject(RejectionReason.MISSING_FIELD, "snapshot misses asOfDate or prices");
            }
            if (!seen.add(s.asOfDate())) {
                throw reject(RejectionReason.DUPLICATE_SNAPSHOT_DATE, "duplicate snapshot date " + s.asOfDate());
            }
            for (Map.Entry<String, Double> e : s.prices().entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null) {
                    throw reject(RejectionReason.MISSING_FIELD, "snapshot " + s.asOfDate() + " has blank assetId or null price");
                }
                if (!Double.isFinite(e.getValue()) || e.getValue() <= 0.0) {
                    throw reject(RejectionReason.NON_POSITIVE_PRICE,
                            "snapshot " + s.asOfDate() + " asset " + e.getKey() + " price=" + e.getValue());
                }
            }
            if (prev != null && s.asOfDate().isBefore(prev)) {
                outOfOrder = true;
            }
            prev = s.asOfDate();
        }
        List<MarketSnapshot> sorted = new ArrayList<>(snapshots);
        sorted.sort(Comparator.comparing(MarketSnapshot::asOfDate));
        if (outOfOrder) {
            String w = "snapshots arrived out of chronological order; normalized to ascending dates (deterministic)";
            warnings.add(w);
            log.info("validation warning: {}", w);
        }
        return List.copyOf(sorted);
    }

    public void validateScenario(ScenarioDefinition scenario, Portfolio portfolio) {
        if (scenario == null || scenario.scenarioId() == null || scenario.scenarioId().isBlank()
                || scenario.version() == null || scenario.version().isBlank()) {
            throw reject(RejectionReason.MISSING_FIELD, "scenarioId and version are required");
        }
        if (scenario.shocks() == null) {
            throw reject(RejectionReason.MISSING_FIELD, "scenario shocks map is required (may be empty)");
        }
        for (Map.Entry<String, Double> e : scenario.shocks().entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null || !Double.isFinite(e.getValue())) {
                throw reject(RejectionReason.MISSING_FIELD, "scenario shock has blank assetId or invalid value");
            }
        }
        Set<String> heldAssets = new HashSet<>();
        for (Position p : portfolio.positions()) {
            heldAssets.add(p.assetId());
        }
        for (String shocked : scenario.shocks().keySet()) {
            if (!heldAssets.contains(shocked)) {
                throw reject(RejectionReason.UNKNOWN_ASSET,
                        "scenario shocks asset " + shocked + " not held by portfolio " + portfolio.portfolioId());
            }
        }
        if (scenario.minHistoryPoints() < 0 || scenario.minTailSamples() < 1) {
            throw reject(RejectionReason.MISSING_FIELD, "minHistoryPoints>=0 and minTailSamples>=1 required");
        }
        if (scenario.correlation() != null) {
            validateCorrelationMatrix(scenario.correlation());
            Set<String> matrixAssets = new HashSet<>(scenario.correlation().assetIds());
            if (!matrixAssets.containsAll(heldAssets)) {
                throw reject(RejectionReason.INVALID_CORRELATION_MATRIX,
                        "correlation matrix does not cover all held assets " + heldAssets);
            }
        }
    }

    public void validateCorrelationMatrix(CorrelationMatrix matrix) {
        List<String> ids = matrix.assetIds();
        List<List<Double>> m = matrix.matrix();
        if (ids == null || m == null || ids.isEmpty() || m.size() != ids.size()) {
            throw reject(RejectionReason.INVALID_CORRELATION_MATRIX, "matrix dimension does not match assetIds");
        }
        if (new HashSet<>(ids).size() != ids.size()) {
            throw reject(RejectionReason.INVALID_CORRELATION_MATRIX, "duplicate assetId in correlation matrix");
        }
        int n = ids.size();
        for (int i = 0; i < n; i++) {
            List<Double> row = m.get(i);
            if (row == null || row.size() != n) {
                throw reject(RejectionReason.INVALID_CORRELATION_MATRIX, "row " + i + " has wrong dimension");
            }
            for (int j = 0; j < n; j++) {
                Double v = row.get(j);
                if (v == null || !Double.isFinite(v)) {
                    throw reject(RejectionReason.INVALID_CORRELATION_MATRIX, "entry [" + i + "][" + j + "] invalid");
                }
                if (Math.abs(v - m.get(j).get(i)) > 1e-9) {
                    throw reject(RejectionReason.INVALID_CORRELATION_MATRIX,
                            "matrix not symmetric at [" + i + "][" + j + "]");
                }
                if (i != j && (v < -1.0 - 1e-9 || v > 1.0 + 1e-9)) {
                    throw reject(RejectionReason.INVALID_CORRELATION_MATRIX,
                            "correlation out of [-1,1] at [" + i + "][" + j + "]: " + v);
                }
            }
            if (Math.abs(row.get(i) - 1.0) > 1e-9) {
                throw reject(RejectionReason.INVALID_CORRELATION_MATRIX, "diagonal [" + i + "] must be 1.0, got " + row.get(i));
            }
        }
        // 半正定校验：Cholesky 分解失败即非半正定
        double[][] a = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                a[i][j] = m.get(i).get(j);
            }
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = a[i][j];
                for (int k = 0; k < j; k++) {
                    sum -= a[i][k] * a[j][k];
                }
                if (i == j) {
                    if (sum < -1e-9) {
                        throw reject(RejectionReason.INVALID_CORRELATION_MATRIX,
                                "matrix is not positive semidefinite (pivot " + i + " = " + sum + ")");
                    }
                    a[i][j] = Math.sqrt(Math.max(sum, 0.0));
                } else {
                    a[i][j] = a[j][j] > 1e-12 ? sum / a[j][j] : 0.0;
                }
            }
        }
    }

    private DataValidationException reject(RejectionReason reason, String message) {
        log.info("input rejected: reason={} detail={}", reason, message);
        return new DataValidationException(reason, message);
    }
}
