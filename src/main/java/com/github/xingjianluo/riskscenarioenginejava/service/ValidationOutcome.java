package com.github.xingjianluo.riskscenarioenginejava.service;

import com.github.xingjianluo.riskscenarioenginejava.model.RejectCode;

/** 输入校验结论：通过（含归一化输入）或拒绝（含可区分原因码）。 */
public record ValidationOutcome(boolean accepted,
                                ValidatedInput input,
                                RejectCode rejectCode,
                                String rejectReason) {

    public static ValidationOutcome ok(ValidatedInput input) {
        return new ValidationOutcome(true, input, null, null);
    }

    public static ValidationOutcome rejected(RejectCode code, String reason) {
        return new ValidationOutcome(false, null, code, reason);
    }
}
