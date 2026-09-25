package com.github.xingjianluo.riskscenarioenginejava.model;

import java.util.List;

/** 组合：组合 ID + 持仓明细。 */
public record Portfolio(String portfolioId, List<Holding> holdings) {
}
