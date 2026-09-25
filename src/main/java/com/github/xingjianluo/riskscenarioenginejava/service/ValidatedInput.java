package com.github.xingjianluo.riskscenarioenginejava.service;

import com.github.xingjianluo.riskscenarioenginejava.model.MarketSnapshot;
import com.github.xingjianluo.riskscenarioenginejava.model.Portfolio;
import com.github.xingjianluo.riskscenarioenginejava.model.ScenarioDefinition;

import java.util.List;

/** 归一化后的试算输入：组合保持提交顺序，快照已按日期升序重排。 */
public record ValidatedInput(Portfolio portfolio,
                             List<MarketSnapshot> sortedSnapshots,
                             MarketSnapshot latestSnapshot,
                             ScenarioDefinition scenario,
                             List<String> notes) {
}
