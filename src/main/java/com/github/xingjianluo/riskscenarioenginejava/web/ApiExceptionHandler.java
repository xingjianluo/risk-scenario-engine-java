package com.github.xingjianluo.riskscenarioenginejava.web;

import com.github.xingjianluo.riskscenarioenginejava.model.ResultStatus;
import com.github.xingjianluo.riskscenarioenginejava.model.RiskResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

/**
 * 协议层异常处理：请求体缺失/畸形等无法进入业务校验的问题，
 * 统一以 HTTP 400 + FAILED 结构返回，便于调用方与业务 REJECTED 明确区分
 * （FAILED=协议/技术问题；REJECTED=数据被加载但不合法）。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public RiskResult unreadable(HttpMessageNotReadableException e) {
        return new RiskResult("(anonymous)", null, null, null,
                ResultStatus.FAILED, null,
                "请求体无法解析（缺少字段或JSON畸形）：" + e.getMostSpecificCause().getMessage(),
                0d, 0d, 0d, 0d, 0d, 0d, 0, null, List.of(),
                Instant.now().toEpochMilli(), 0);
    }

    /** 兜底：未预期异常返回 500 + FAILED，绝不返回堆栈细节。 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public RiskResult unexpected(Exception e) {
        return new RiskResult("(anonymous)", null, null, null,
                ResultStatus.FAILED, null,
                "服务端技术异常：" + e.getClass().getSimpleName(),
                0d, 0d, 0d, 0d, 0d, 0d, 0, null, List.of(),
                Instant.now().toEpochMilli(), 0);
    }
}
