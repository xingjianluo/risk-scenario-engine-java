package com.github.xingjianluo.riskscenarioenginejava.web;

import com.github.xingjianluo.riskscenarioenginejava.model.BatchTrialRequest;
import com.github.xingjianluo.riskscenarioenginejava.model.RiskResult;
import com.github.xingjianluo.riskscenarioenginejava.model.TrialRequest;
import com.github.xingjianluo.riskscenarioenginejava.service.TrialExecutionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 风险试算 REST 接口。
 *
 * <p>业务结论（ACCEPTED/REJECTED/FAILED）统一以 HTTP 200 + 结果体返回，
 * 由 {@code status} 字段区分；重复提交相同 requestId 返回幂等的同一结论。</p>
 */
@RestController
@RequestMapping("/api/risk")
public class RiskController {

    private final TrialExecutionService executionService;

    public RiskController(TrialExecutionService executionService) {
        this.executionService = executionService;
    }

    /** 单个试算（同步返回计算结论）。 */
    @PostMapping("/trials")
    public RiskResult submitTrial(@RequestBody TrialRequest request) {
        return executionService.submit(request).join();
    }

    /** 批量试算：并发执行、相互隔离，返回顺序与请求顺序一致。 */
    @PostMapping("/trials/batch")
    public List<RiskResult> submitBatch(@RequestBody BatchTrialRequest request) {
        return executionService.submitAll(request == null ? List.of() : request.trials());
    }

    /** 查询已提交试算结论（幂等复用）；不存在或尚未完成时返回 404。 */
    @GetMapping("/trials/{requestId}")
    public ResponseEntity<RiskResult> getTrial(@PathVariable String requestId) {
        RiskResult result = executionService.getIfPresent(requestId);
        return result == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(result);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
