package com.github.xingjianluo.riskscenarioenginejava.model;

import java.time.LocalDate;
import java.util.Map;

/** 单期行情快照：日期 -> 标的最新价。 */
public record MarketSnapshot(LocalDate snapshotDate, Map<String, Double> prices) {
}
