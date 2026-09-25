package com.github.xingjianluo.riskscenarioenginejava.engine;

import com.github.xingjianluo.riskscenarioenginejava.model.RejectCode;

import java.util.List;

/** 场景模拟结论：成功（含损失样本）或确定性拒绝。 */
public record SimulationOutcome(boolean accepted,
                                double[] lossSamples,
                                List<String> notes,
                                RejectCode rejectCode,
                                String rejectReason) {

    public static SimulationOutcome ok(double[] lossSamples, List<String> notes) {
        return new SimulationOutcome(true, lossSamples, notes, null, null);
    }

    public static SimulationOutcome rejected(RejectCode code, String reason) {
        return new SimulationOutcome(false, null, List.of(), code, reason);
    }
}
