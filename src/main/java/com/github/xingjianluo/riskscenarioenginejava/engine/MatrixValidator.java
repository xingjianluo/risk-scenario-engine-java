package com.github.xingjianluo.riskscenarioenginejava.engine;

import com.github.xingjianluo.riskscenarioenginejava.model.RejectCode;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 相关性矩阵合法性校验。
 *
 * <p>依次检查：标的顺序非空且无重复、矩阵存在且为方阵、维度一致、元素有限、
 * 对角线恰为 1、关于对角线对称、对称半正定（带容差的 Cholesky 分解）。
 * 任一不满足即返回对应 {@link RejectCode}，绝不静默修正。</p>
 */
@Component
public class MatrixValidator {

    /** 相关系数允许的最大偏差（对角=1、对称性、PSD 判定均使用此容差）。 */
    public static final double EPS = 1e-9;

    /**
     * @param instruments     矩阵行列对应的标的顺序
     * @param matrix          相关性矩阵
     * @param heldInstruments 组合实际持有的标的集合，矩阵只允许引用其中标的
     * @return 首个发现的问题原因码；全部合法时返回 {@code null}
     */
    public RejectCode validate(List<String> instruments,
                               double[][] matrix,
                               Set<String> heldInstruments) {
        if (instruments == null) {
            return RejectCode.CORRELATION_INSTRUMENTS_MISSING;
        }
        if (instruments.isEmpty()) {
            return RejectCode.CORRELATION_INSTRUMENTS_MISSING;
        }
        Set<String> unique = new HashSet<>();
        for (String instrument : instruments) {
            if (instrument == null || instrument.isBlank()) {
                return RejectCode.CORRELATION_INSTRUMENTS_MISSING;
            }
            if (!unique.add(instrument)) {
                return RejectCode.CORRELATION_DUPLICATE_INSTRUMENT;
            }
            if (!heldInstruments.contains(instrument)) {
                return RejectCode.CORRELATION_INSTRUMENT_NOT_HELD;
            }
        }
        if (matrix == null || matrix.length != instruments.size()) {
            return RejectCode.CORRELATION_DIMENSION_MISMATCH;
        }
        int n = instruments.size();
        for (double[] row : matrix) {
            if (row == null || row.length != n) {
                return RejectCode.CORRELATION_NOT_SQUARE;
            }
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (!Double.isFinite(matrix[i][j])) {
                    return RejectCode.CORRELATION_VALUE_NONFINITE;
                }
                if (i == j && Math.abs(matrix[i][j] - 1.0) > EPS) {
                    return RejectCode.CORRELATION_DIAGONAL_INVALID;
                }
                if (Math.abs(matrix[i][j] - matrix[j][i]) > EPS) {
                    return RejectCode.CORRELATION_NOT_SYMMETRIC;
                }
            }
        }
        if (!isPositiveSemidefinite(matrix, n)) {
            return RejectCode.CORRELATION_NOT_PSD;
        }
        return null;
    }

    /**
     * 带容差的 Cholesky 判定：若存在某对角元素在数值容差修正后仍为负，
     * 则矩阵不是对称半正定。
     */
    private boolean isPositiveSemidefinite(double[][] matrix, int n) {
        double[][] lower = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = matrix[i][j];
                for (int k = 0; k < j; k++) {
                    sum -= lower[i][k] * lower[j][k];
                }
                if (i == j) {
                    if (sum < -EPS) {
                        return false;
                    }
                    lower[i][j] = Math.sqrt(Math.max(sum, 0.0));
                } else {
                    double pivot = lower[j][j];
                    if (pivot < Math.sqrt(EPS)) {
                        // 近奇异：避免除零，视为该方向无方差贡献
                        lower[i][j] = 0.0;
                    } else {
                        lower[i][j] = sum / pivot;
                    }
                }
            }
        }
        return true;
    }
}
