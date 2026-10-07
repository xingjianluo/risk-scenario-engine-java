package com.github.xingjianluo.riskscenarioenginejava.api;

import com.github.xingjianluo.riskscenarioenginejava.task.TaskSnapshot;
import com.github.xingjianluo.riskscenarioenginejava.task.TrialTaskService;
import com.github.xingjianluo.riskscenarioenginejava.trial.TrialRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 任务化试算接口：提交即返回 taskId，凭 taskId 查询/取消。
 */
@RestController
@RequestMapping("/api/trial-tasks")
public class TrialTaskController {

    private final TrialTaskService taskService;

    public TrialTaskController(TrialTaskService taskService) {
        this.taskService = taskService;
    }

    /** 提交任务，立即返回快照（QUEUED/RUNNING/REJECTED），不等待计算完成。 */
    @PostMapping
    public TaskSnapshot submit(@RequestBody TrialRequest request) {
        return taskService.submit(request);
    }

    /** 查询任务状态；未知 taskId 返回 404。 */
    @GetMapping("/{taskId}")
    public ResponseEntity<TaskSnapshot> query(@PathVariable String taskId) {
        TaskSnapshot snap = taskService.query(taskId);
        return snap == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(snap);
    }

    /** 取消未完成任务；已终态则返回当前状态（幂等）；未知 taskId 返回 404。 */
    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<TaskSnapshot> cancel(@PathVariable String taskId) {
        TaskSnapshot snap = taskService.cancel(taskId);
        return snap == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(snap);
    }

    @GetMapping("/metrics")
    public Map<String, Long> metrics() {
        return taskService.metrics().asMap();
    }
}
