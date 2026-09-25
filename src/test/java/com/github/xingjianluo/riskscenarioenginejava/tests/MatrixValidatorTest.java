package com.github.xingjianluo.riskscenarioenginejava.tests;

import com.github.xingjianluo.riskscenarioenginejava.engine.MatrixValidator;
import com.github.xingjianluo.riskscenarioenginejava.model.RejectCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 相关性矩阵合法性边界：非方阵、非对称、对角不为1、非PSD、引用未持有标的等。 */
class MatrixValidatorTest {

    private static final Logger LOG = Logger.getLogger(MatrixValidatorTest.class.getName());

    private final MatrixValidator validator = new MatrixValidator();
    private final Set<String> held = Set.of("A", "B");

    @Test
    @DisplayName("合法单位阵与强相关对称PSD矩阵应通过")
    void validMatricesPass() {
        double[][] identity = {{1, 0}, {0, 1}};
        double[][] strong = {{1, 0.7}, {0.7, 1}};
        LOG.info("输入摘要: instruments=[A,B], 矩阵=单位阵/0.7相关阵; 判定依据: 方阵+对角1+对称+Cholesky PSD");
        assertNull(validator.validate(List.of("A", "B"), identity, held));
        assertNull(validator.validate(List.of("A", "B"), strong, held));
    }

    @Test
    @DisplayName("非方阵 -> CORRELATION_NOT_SQUARE")
    void nonSquareRejected() {
        double[][] matrix = {{1, 0}, {0, 1, 0}};
        RejectCode code = validator.validate(List.of("A", "B"), matrix, held);
        LOG.info("判定依据: 行长度不一致; 拒绝原因=" + code);
        assertEquals(RejectCode.CORRELATION_NOT_SQUARE, code);
    }

    @Test
    @DisplayName("维度与标的数不一致 -> CORRELATION_DIMENSION_MISMATCH")
    void dimensionMismatchRejected() {
        double[][] matrix = {{1}};
        RejectCode code = validator.validate(List.of("A", "B"), matrix, held);
        LOG.info("判定依据: 矩阵1x1但标的数为2; 拒绝原因=" + code);
        assertEquals(RejectCode.CORRELATION_DIMENSION_MISMATCH, code);
    }

    @Test
    @DisplayName("对角线不为1 -> CORRELATION_DIAGONAL_INVALID")
    void invalidDiagonalRejected() {
        double[][] matrix = {{0.9, 0.1}, {0.1, 1}};
        RejectCode code = validator.validate(List.of("A", "B"), matrix, held);
        LOG.info("判定依据: 对角线元素0.9; 拒绝原因=" + code);
        assertEquals(RejectCode.CORRELATION_DIAGONAL_INVALID, code);
    }

    @Test
    @DisplayName("非对称矩阵 -> CORRELATION_NOT_SYMMETRIC")
    void asymmetricRejected() {
        double[][] matrix = {{1, 0.8}, {0.2, 1}};
        RejectCode code = validator.validate(List.of("A", "B"), matrix, held);
        LOG.info("判定依据: C[0,1]=0.8 与 C[1,0]=0.2; 拒绝原因=" + code);
        assertEquals(RejectCode.CORRELATION_NOT_SYMMETRIC, code);
    }

    @Test
    @DisplayName("相关系数绝对值大于1的非PSD矩阵 -> CORRELATION_NOT_PSD")
    void nonPsdRejected() {
        double[][] matrix = {{1, 1.5}, {1.5, 1}};
        RejectCode code = validator.validate(List.of("A", "B"), matrix, held);
        LOG.info("判定依据: Cholesky对角元素为负; 拒绝原因=" + code);
        assertEquals(RejectCode.CORRELATION_NOT_PSD, code);
    }

    @Test
    @DisplayName("引用组合未持有标的 -> CORRELATION_INSTRUMENT_NOT_HELD")
    void unknownInstrumentRejected() {
        double[][] matrix = {{1, 0}, {0, 1}};
        RejectCode code = validator.validate(List.of("A", "C"), matrix, held);
        LOG.info("判定依据: 组合仅持有[A,B]，矩阵引用C; 拒绝原因=" + code);
        assertEquals(RejectCode.CORRELATION_INSTRUMENT_NOT_HELD, code);
    }

    @Test
    @DisplayName("标的重复 -> CORRELATION_DUPLICATE_INSTRUMENT")
    void duplicateInstrumentRejected() {
        double[][] matrix = {{1, 0}, {0, 1}};
        RejectCode code = validator.validate(List.of("A", "A"), matrix, held);
        LOG.info("判定依据: 标的顺序中A出现两次; 拒绝原因=" + code);
        assertEquals(RejectCode.CORRELATION_DUPLICATE_INSTRUMENT, code);
    }

    @Test
    @DisplayName("非有限元素 -> CORRELATION_VALUE_NONFINITE")
    void nonFiniteRejected() {
        double[][] matrix = {{1, Double.NaN}, {Double.NaN, 1}};
        RejectCode code = validator.validate(List.of("A", "B"), matrix, held);
        LOG.info("判定依据: 矩阵含NaN; 拒绝原因=" + code);
        assertEquals(RejectCode.CORRELATION_VALUE_NONFINITE, code);
    }
}
