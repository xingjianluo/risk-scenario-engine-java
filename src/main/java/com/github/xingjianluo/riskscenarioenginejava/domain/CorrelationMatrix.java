package com.github.xingjianluo.riskscenarioenginejava.domain;

import java.util.List;

/**
 * 相关性矩阵：assetIds 定义行列顺序，matrix 为 N x N。
 */
public record CorrelationMatrix(List<String> assetIds, List<List<Double>> matrix) {
}
