package com.github.xingjianluo.riskscenarioenginejava.domain;

/**
 * 组合持仓：instrumentId 持有的资产 assetId，数量 quantity。
 * 估值口径：value = quantity * price(assetId)。
 */
public record Position(String instrumentId, String assetId, double quantity) {
}
