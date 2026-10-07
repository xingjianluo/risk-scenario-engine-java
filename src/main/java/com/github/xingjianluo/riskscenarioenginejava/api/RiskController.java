package com.github.xingjianluo.riskscenarioenginejava.api;

import com.github.xingjianluo.riskscenarioenginejava.trial.TaskMetrics;
import com.github.xingjianluo.riskscenarioenginejava.trial.TrialMetrics;
import com.github.xingjianluo.riskscenarioenginejava.trial.TrialRequest;
import com.github.xingjianluo.riskscenarioenginejava.trial.TrialResult;
import com.github.xingjianluo.riskscenarioenginejava.trial.TrialService;
import com.github.xingjianluo.riskscenarioenginejava.trial.TrialTaskService;
import com.github.xingjianluo.riskscenarioenginejava.trial.TrialTaskView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/trials")
public class RiskController {

    private final TrialService trialService;
    private final TrialTaskService trialTaskService;

    public RiskController(TrialService trialService, TrialTaskService trialTaskService) {
        this.trialService = trialService;
        this.trialTaskService = trialTaskService;
    }

    /** 旧版同步用法：提交后等待结果。保留兼容。 */
    @PostMapping
    public CompletableFuture<TrialResult> submit(@RequestBody TrialRequest request) {
        return trialService.submit(request);
    }

    @GetMapping("/metrics")
    public Map<String, Long> metrics() {
        return trialService.metrics().asMap();
    }

    /** 任务化用法：提交即返回票据（ticketId = idempotencyKey），不等待计算完成。 */
    @PostMapping("/tasks")
    public TrialTaskView submitTask(@RequestBody TrialRequest request) {
        return trialTaskService.submit(request);
    }

    /** 凭票据编号查询当前状态；未知编号返回 404。 */
    @GetMapping("/tasks/{ticketId}")
    public ResponseEntity<TrialTaskView> queryTask(@PathVariable String ticketId) {
        TrialTaskView view = trialTaskService.query(ticketId);
        return view.status() == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(view);
    }

    /** 取消未完成的试算；已终态为幂等空操作；未知编号返回 404。 */
    @PostMapping("/tasks/{ticketId}/cancel")
    public ResponseEntity<TrialTaskView> cancelTask(@PathVariable String ticketId) {
        TrialTaskView view = trialTaskService.cancel(ticketId);
        return view.status() == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(view);
    }

    /** 任务层容量与生命周期指标。 */
    @GetMapping("/tasks/metrics")
    public Map<String, Long> taskMetrics() {
        TaskMetrics m = trialTaskService.metrics();
        return m.asMap();
    }
}
