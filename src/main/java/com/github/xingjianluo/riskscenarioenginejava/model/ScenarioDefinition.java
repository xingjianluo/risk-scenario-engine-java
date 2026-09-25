package com.github.xingjianluo.riskscenarioenginejava.model;

import java.util.List;
import java.util.Map;

/**
 * 场景定义（不可变）。
 *
 * @param scenarioId           场景 ID
 * @param version              场景版本，结果中原样回填，跨版本结论标注不可比
 * @param type                 场景类型
 * @param shocks               场景冲击：标的 -> 价格冲击幅度（如 -0.10 表示下跌 10%）
 * @param correlationInstruments 相关性矩阵的标的顺序
 * @param correlation          相关性调整矩阵（与 correlationInstruments 对齐），仅 CORRELATION_ADJUST 使用
 * @param historicalReturns    历史收益率序列：标的 -> 按期排列的收益率，仅 HISTORICAL_SIMULATION 使用
 * @param seed                 蒙特卡洛随机种子，保证可复现
 * @param sampleCount          相关性调整场景的抽样次数
 */
public record ScenarioDefinition(String scenarioId,
                                 String version,
                                 ScenarioType type,
                                 Map<String, Double> shocks,
                                 List<String> correlationInstruments,
                                 double[][] correlation,
                                 Map<String, List<Double>> historicalReturns,
                                 long seed,
                                 int sampleCount) {
}
