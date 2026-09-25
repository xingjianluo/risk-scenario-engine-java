package com.github.xingjianluo.riskscenarioenginejava.model;

/** 场景类型：确定性场景冲击、相关性调整（带种子的蒙特卡洛）、历史模拟。 */
public enum ScenarioType {
    SCENARIO_SHOCK,
    CORRELATION_ADJUST,
    HISTORICAL_SIMULATION
}
