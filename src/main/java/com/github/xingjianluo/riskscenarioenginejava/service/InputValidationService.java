package com.github.xingjianluo.riskscenarioenginejava.service;

import com.github.xingjianluo.riskscenarioenginejava.model.Holding;
import com.github.xingjianluo.riskscenarioenginejava.model.MarketSnapshot;
import com.github.xingjianluo.riskscenarioenginejava.model.Portfolio;
import com.github.xingjianluo.riskscenarioenginejava.model.RejectCode;
import com.github.xingjianluo.riskscenarioenginejava.model.ScenarioDefinition;
import com.github.xingjianluo.riskscenarioenginejava.model.ScenarioType;
import com.github.xingjianluo.riskscenarioenginejava.model.TrialRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 试算输入的加载校验与归一化。
 *
 * <p>原则：任何非法或不完整数据都返回带 {@link RejectCode} 的确定性拒绝，绝不静默跳过；
 * 唯一允许的“改写”是把乱序快照按日期升序重排，并在 notes 中明确说明。</p>
 */
@Service
public class InputValidationService {

    public ValidationOutcome validate(TrialRequest request) {
        if (request == null) {
            return ValidationOutcome.rejected(RejectCode.PORTFOLIO_MISSING, "请求体为空");
        }

        Portfolio portfolio = request.portfolio();
        if (portfolio == null) {
            return reject(RejectCode.PORTFOLIO_MISSING, request, "缺少组合持仓");
        }
        if (isBlank(portfolio.portfolioId())) {
            return reject(RejectCode.PORTFOLIO_ID_MISSING, request, "组合 ID 缺失");
        }
        if (portfolio.holdings() == null || portfolio.holdings().isEmpty()) {
            return reject(RejectCode.PORTFOLIO_EMPTY, request, "组合持仓为空");
        }

        Set<String> heldInstruments = new LinkedHashSet<>();
        for (Holding holding : portfolio.holdings()) {
            if (holding == null || isBlank(holding.instrumentId())) {
                return reject(RejectCode.HOLDING_INSTRUMENT_MISSING, request,
                        "存在标的 ID 缺失的持仓行");
            }
            if (!Double.isFinite(holding.quantity()) || holding.quantity() == 0.0) {
                return reject(RejectCode.HOLDING_QUANTITY_INVALID, request,
                        "持仓 " + holding.instrumentId() + " 数量为 0 或非有限值");
            }
            if (!heldInstruments.add(holding.instrumentId())) {
                return reject(RejectCode.DUPLICATE_HOLDING, request,
                        "同一标的存在重复持仓行：" + holding.instrumentId());
            }
        }

        List<MarketSnapshot> snapshots = request.snapshots();
        if (snapshots == null) {
            return reject(RejectCode.SNAPSHOT_LIST_MISSING, request, "行情快照列表缺失");
        }
        if (snapshots.isEmpty()) {
            return reject(RejectCode.SNAPSHOT_LIST_EMPTY, request, "行情快照为空，至少需要 1 期");
        }

        List<MarketSnapshot> sorted = new ArrayList<>(snapshots);
        boolean wasSorted = true;
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i - 1) == null || sorted.get(i) == null
                    || sorted.get(i - 1).snapshotDate() == null
                    || sorted.get(i).snapshotDate() == null) {
                return reject(RejectCode.SNAPSHOT_DATE_MISSING, request, "存在无日期的行情快照");
            }
            if (sorted.get(i - 1).snapshotDate().isAfter(sorted.get(i).snapshotDate())) {
                wasSorted = false;
            }
        }
        if (sorted.get(0).snapshotDate() == null) {
            return reject(RejectCode.SNAPSHOT_DATE_MISSING, request, "存在无日期的行情快照");
        }
        if (!wasSorted) {
            sorted.sort(Comparator.comparing(MarketSnapshot::snapshotDate));
        }
        Set<LocalDate> seenDates = new HashSet<>();
        for (MarketSnapshot snapshot : sorted) {
            if (!seenDates.add(snapshot.snapshotDate())) {
                return reject(RejectCode.DUPLICATE_SNAPSHOT, request,
                        "存在重复日期的行情快照：" + snapshot.snapshotDate());
            }
            if (snapshot.prices() == null) {
                return reject(RejectCode.SNAPSHOT_PRICES_MISSING, request,
                        "快照 " + snapshot.snapshotDate() + " 缺少价格表");
            }
        }

        MarketSnapshot latest = sorted.get(sorted.size() - 1);
        for (String instrument : heldInstruments) {
            Double price = latest.prices().get(instrument);
            if (price == null) {
                return reject(RejectCode.MISSING_PRICE_FOR_INSTRUMENT, request,
                        "最新快照 " + latest.snapshotDate() + " 缺少标的 " + instrument + " 的价格");
            }
            if (!Double.isFinite(price) || price <= 0.0) {
                return reject(RejectCode.PRICE_NON_POSITIVE, request,
                        "标的 " + instrument + " 最新价为空值、非有限值或非正数：" + price);
            }
        }

        ScenarioDefinition scenario = request.scenario();
        if (scenario == null) {
            return reject(RejectCode.SCENARIO_MISSING, request, "缺少场景定义");
        }
        if (isBlank(scenario.scenarioId())) {
            return reject(RejectCode.SCENARIO_ID_MISSING, request, "场景 ID 缺失");
        }
        if (isBlank(scenario.version())) {
            return reject(RejectCode.SCENARIO_VERSION_MISSING, request, "场景版本缺失（无法支持跨期复现）");
        }
        ScenarioType type = scenario.type();
        if (type == null) {
            return reject(RejectCode.SCENARIO_TYPE_MISSING, request, "场景类型缺失");
        }
        ValidationOutcome scenarioCheck = validateScenarioByType(scenario, heldInstruments, request);
        if (scenarioCheck != null) {
            return scenarioCheck;
        }

        List<String> notes = new ArrayList<>();
        if (!wasSorted) {
            notes.add("输入快照存在时序乱序，已按日期升序归一化后计算（共 "
                    + sorted.size() + " 期）");
        }

        ValidatedInput input = new ValidatedInput(portfolio, List.copyOf(sorted), latest, scenario,
                List.copyOf(notes));
        return ValidationOutcome.ok(input);
    }

    private ValidationOutcome validateScenarioByType(ScenarioDefinition scenario,
                                                     Set<String> heldInstruments,
                                                     TrialRequest request) {
        return switch (scenario.type()) {
            case SCENARIO_SHOCK -> validateShock(scenario, heldInstruments, request);
            case CORRELATION_ADJUST -> validateCorrelationScenario(scenario, request);
            case HISTORICAL_SIMULATION -> validateHistoricalShape(scenario, request);
        };
    }

    private ValidationOutcome validateShock(ScenarioDefinition scenario,
                                            Set<String> heldInstruments,
                                            TrialRequest request) {
        Map<String, Double> shocks = scenario.shocks();
        if (shocks == null) {
            return reject(RejectCode.SHOCK_MAP_MISSING, request, "场景冲击表缺失");
        }
        if (shocks.isEmpty()) {
            return reject(RejectCode.SHOCK_EMPTY, request, "场景冲击表为空");
        }
        for (Map.Entry<String, Double> entry : shocks.entrySet()) {
            if (entry.getValue() == null || !Double.isFinite(entry.getValue())) {
                return reject(RejectCode.SHOCK_VALUE_NONFINITE, request,
                        "标的 " + entry.getKey() + " 的冲击幅度为空值或非有限值");
            }
            if (!heldInstruments.contains(entry.getKey())) {
                return reject(RejectCode.SHOCK_REFERENCES_UNKNOWN_INSTRUMENT, request,
                        "场景冲击引用了组合中不存在的标的：" + entry.getKey());
            }
        }
        return null;
    }

    private ValidationOutcome validateCorrelationScenario(ScenarioDefinition scenario,
                                                          TrialRequest request) {
        if (scenario.sampleCount() < 1) {
            return reject(RejectCode.MC_SAMPLE_COUNT_INVALID, request,
                    "蒙特卡洛抽样次数必须为正整数：" + scenario.sampleCount());
        }
        return null;
    }

    private ValidationOutcome validateHistoricalShape(ScenarioDefinition scenario,
                                                      TrialRequest request) {
        var returns = scenario.historicalReturns();
        if (returns == null) {
            return reject(RejectCode.HISTORICAL_RETURNS_MISSING, request, "历史收益率序列缺失");
        }
        if (returns.isEmpty()) {
            return reject(RejectCode.HISTORICAL_SERIES_EMPTY, request, "历史收益率序列为空");
        }
        Integer length = null;
        for (Map.Entry<String, List<Double>> entry : returns.entrySet()) {
            List<Double> series = entry.getValue();
            if (series == null || series.isEmpty()) {
                return reject(RejectCode.HISTORICAL_SERIES_EMPTY, request,
                        "标的 " + entry.getKey() + " 的历史序列为空");
            }
            for (Double r : series) {
                if (r == null || !Double.isFinite(r)) {
                    return reject(RejectCode.HISTORICAL_RETURN_NONFINITE, request,
                            "标的 " + entry.getKey() + " 的历史收益率存在空值或非有限值");
                }
            }
            if (length == null) {
                length = series.size();
            } else if (series.size() != length) {
                return reject(RejectCode.HISTORICAL_SERIES_LENGTH_MISMATCH, request,
                        "标的 " + entry.getKey() + " 的历史序列长度 " + series.size()
                                + " 与先前标的 " + length + " 不一致");
            }
        }
        return null;
    }

    private ValidationOutcome reject(RejectCode code, TrialRequest request, String reason) {
        return ValidationOutcome.rejected(code, reason);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
