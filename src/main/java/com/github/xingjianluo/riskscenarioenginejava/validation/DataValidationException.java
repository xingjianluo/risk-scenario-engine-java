package com.github.xingjianluo.riskscenarioenginejava.validation;

import com.github.xingjianluo.riskscenarioenginejava.domain.RejectionReason;

/**
 * 数据校验拒绝异常：携带可区分的拒绝原因与说明。
 */
public class DataValidationException extends RuntimeException {
    private final RejectionReason reason;

    public DataValidationException(RejectionReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectionReason reason() {
        return reason;
    }
}
