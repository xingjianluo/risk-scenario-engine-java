package com.github.xingjianluo.riskscenarioenginejava.model;

/** 单条持仓： instrumentId 允许做多（正）与做空（负），不允许为 0 或非有限值。 */
public record Holding(String instrumentId, double quantity) {
}
