package com.github.xingjianluo.riskscenarioenginejava.domain;

import java.time.LocalDate;
import java.util.Map;

/**
 * 单期行情快照：asOfDate 当日各资产收盘价。
 */
public record MarketSnapshot(LocalDate asOfDate, Map<String, Double> prices) {
}
