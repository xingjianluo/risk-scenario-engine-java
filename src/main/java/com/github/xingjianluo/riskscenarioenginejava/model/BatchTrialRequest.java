package com.github.xingjianluo.riskscenarioenginejava.model;

import java.util.List;

/** 批量试算请求：各试算并发执行、相互隔离。 */
public record BatchTrialRequest(List<TrialRequest> trials) {
}
