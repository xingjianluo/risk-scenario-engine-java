package com.github.xingjianluo.riskscenarioenginejava.task;

import com.github.xingjianluo.riskscenarioenginejava.domain.RejectionReason;
import com.github.xingjianluo.riskscenarioenginejava.domain.TrialStatus;
import com.github.xingjianluo.riskscenarioenginejava.engine.ScenarioAnalysisService;
import com.github.xingjianluo.riskscenarioenginejava.trial.TrialRequest;
import com.github.xingjianluo.riskscenarioenginejava.trial.TrialResult;
import com.github.xingjianluo.riskscenarioenginejava.validation.DataValidationException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 任务化试算层：
 * - 提交即返回 taskId（= idempotencyKey）与当前快照，不阻塞等待计算完成；
 * - 凭 taskId 可随时查询 QUEUED / RUNNING / 各终态；同一 taskId 反复查询稳定一致，查询不触发计算；
 * - 取消：QUEUED/RUNNING 可取消为独立终态 CANCELLED，与超时、失败、拒绝明确区分；
 *   取消后释放容量并中断工作线程，迟到的计算结果不得覆盖 CANCELLED；
 * - 容量：同时执行上限 maxConcurrent，超出排队（上限 maxQueue），排满即拒绝（CAPACITY_EXCEEDED，
 *   与输入类拒绝原因可区分）；同 key 重复提交不重复占坑、不重复计数；
 * - 指标自洽：submitted = queued + running + succeeded + rejected + failed + timedOut + cancelled，
 *   每个唯一 taskId 的终态恰好计入一次。
 */
@Service
public class TrialTaskService {

    private static final Logger log = LoggerFactory.getLogger(TrialTaskService.class);
    private static final long DEFAULT_TIMEOUT_MS = 10_000;

    private final ScenarioAnalysisService analysisService;
    private final int maxConcurrent;
    private final int maxQueue;
    private final ExecutorService workerPool;
    private final ScheduledExecutorService timeoutScheduler;

    private final ConcurrentHashMap<String, TaskRecord> tasks = new ConcurrentHashMap<>();

    /** 接纳控制与状态迁移锁：running、queue、各 record 的 status 均在其保护下变更。 */
    private final Object lock = new Object();
    private int running = 0;
    private final ArrayDeque<TaskRecord> queue = new ArrayDeque<>();

    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong deduplicated = new AtomicLong();
    private final AtomicLong succeeded = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong capacityRejected = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong timedOut = new AtomicLong();
    private final AtomicLong cancelled = new AtomicLong();

    @org.springframework.beans.factory.annotation.Autowired
    public TrialTaskService(ScenarioAnalysisService analysisService) {
        this(analysisService, Math.max(2, Runtime.getRuntime().availableProcessors()), 100);
    }

    public TrialTaskService(ScenarioAnalysisService analysisService, int maxConcurrent, int maxQueue) {
        this.analysisService = analysisService;
        this.maxConcurrent = maxConcurrent;
        this.maxQueue = maxQueue;
        this.workerPool = Executors.newFixedThreadPool(maxConcurrent, r -> {
            Thread t = new Thread(r, "trial-task-worker");
            t.setDaemon(true);
            return t;
        });
        this.timeoutScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "trial-task-timeout");
            t.setDaemon(true);
            return t;
        });
    }

    /** 提交任务，立即返回快照（含 taskId）。同 key 重复提交返回已有任务的快照，不重复占容量。 */
    public TaskSnapshot submit(TrialRequest request) {
        long now = System.currentTimeMillis();
        if (request == null || request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            submitted.incrementAndGet();
            rejected.incrementAndGet();
            log.info("[task-submit] rejected: reason={} detail=idempotencyKey is required",
                    RejectionReason.MISSING_FIELD);
            return new TaskSnapshot(null, TrialStatus.REJECTED,
                    TrialResult.rejected(null, RejectionReason.MISSING_FIELD, "idempotencyKey is required"),
                    now, null, now, "idempotencyKey is required");
        }
        String key = request.idempotencyKey();
        AtomicBoolean first = new AtomicBoolean(false);
        TaskRecord rec = tasks.computeIfAbsent(key, k -> {
            first.set(true);
            return new TaskRecord(k, request, now);
        });
        if (!first.get()) {
            long n = deduplicated.incrementAndGet();
            TaskSnapshot snap = snapshotOf(rec);
            log.info("[task-submit] duplicate ignored: taskId={} status={} dedupCount={}", key, snap.status(), n);
            return snap;
        }
        submitted.incrementAndGet();

        List<TaskRecord> toDispatch = new ArrayList<>();
        synchronized (lock) {
            if (running < maxConcurrent) {
                rec.status = TrialStatus.RUNNING;
                rec.startedAt = now;
                running++;
                toDispatch.add(rec);
                log.info("[task-submit] admitted: taskId={} -> RUNNING running={}/{}", key, running, maxConcurrent);
            } else if (queue.size() < maxQueue) {
                rec.status = TrialStatus.QUEUED;
                queue.add(rec);
                log.info("[task-submit] queued: taskId={} -> QUEUED queued={}/{} running={}/{}",
                        key, queue.size(), maxQueue, running, maxConcurrent);
            } else {
                rec.status = TrialStatus.REJECTED;
                rec.result = TrialResult.rejected(key, RejectionReason.CAPACITY_EXCEEDED,
                        "capacity exceeded: running=" + running + "/" + maxConcurrent
                                + " queued=" + queue.size() + "/" + maxQueue);
                rec.finishedAt = now;
                rejected.incrementAndGet();
                capacityRejected.incrementAndGet();
                log.info("[task-submit] rejected: taskId={} reason={} running={}/{} queued={}/{}",
                        key, RejectionReason.CAPACITY_EXCEEDED, running, maxConcurrent, queue.size(), maxQueue);
            }
        }
        toDispatch.forEach(this::dispatch);
        return snapshotOf(rec);
    }

    /** 查询任务状态；未知 taskId 返回 null。查询为只读操作，不触发任何计算。 */
    public TaskSnapshot query(String taskId) {
        TaskRecord rec = tasks.get(taskId);
        return rec == null ? null : snapshotOf(rec);
    }

    /**
     * 取消未完成任务。QUEUED 直接出队；RUNNING 释放容量并中断工作线程，迟到结果不得覆盖。
     * 已终态的任务保持不变（幂等）。未知 taskId 返回 null。
     */
    public TaskSnapshot cancel(String taskId) {
        TaskRecord rec = tasks.get(taskId);
        if (rec == null) {
            log.info("[task-cancel] unknown taskId={}", taskId);
            return null;
        }
        List<TaskRecord> toDispatch = new ArrayList<>();
        synchronized (lock) {
            if (rec.status == TrialStatus.QUEUED) {
                queue.remove(rec);
                rec.status = TrialStatus.CANCELLED;
                rec.result = TrialResult.cancelled(rec.taskId, "cancelled while queued");
                rec.finishedAt = System.currentTimeMillis();
                cancelled.incrementAndGet();
                log.info("[task-cancel] taskId={} QUEUED -> CANCELLED queued={}/{}",
                        rec.taskId, queue.size(), maxQueue);
            } else if (rec.status == TrialStatus.RUNNING) {
                rec.status = TrialStatus.CANCELLED;
                rec.result = TrialResult.cancelled(rec.taskId, "cancelled while running");
                rec.finishedAt = System.currentTimeMillis();
                cancelled.incrementAndGet();
                running--;
                if (rec.timeoutFuture != null) {
                    rec.timeoutFuture.cancel(false);
                }
                Thread t = rec.workerThread;
                if (t != null) {
                    t.interrupt();
                }
                log.info("[task-cancel] taskId={} RUNNING -> CANCELLED running={}/{}",
                        rec.taskId, running, maxConcurrent);
                toDispatch.addAll(drainQueueLocked());
            } else {
                log.info("[task-cancel] taskId={} already terminal: status={}", rec.taskId, rec.status);
            }
        }
        toDispatch.forEach(this::dispatch);
        return snapshotOf(rec);
    }

    public TaskMetrics metrics() {
        synchronized (lock) {
            return new TaskMetrics(submitted.get(), deduplicated.get(), queue.size(), running,
                    succeeded.get(), rejected.get(), capacityRejected.get(),
                    failed.get(), timedOut.get(), cancelled.get());
        }
    }

    /** 仅供测试：当前在册任务数。 */
    public int registeredTasks() {
        return tasks.size();
    }

    // ---- 内部实现 ----

    private void dispatch(TaskRecord rec) {
        long timeoutMs = rec.request.timeoutMs() > 0 ? rec.request.timeoutMs() : DEFAULT_TIMEOUT_MS;
        rec.timeoutFuture = timeoutScheduler.schedule(() -> onTimeout(rec, timeoutMs), timeoutMs, TimeUnit.MILLISECONDS);
        workerPool.execute(() -> {
            rec.workerThread = Thread.currentThread();
            try {
                var report = analysisService.analyze(rec.request.portfolio(), rec.request.snapshots(),
                        rec.request.scenario(), rec.request.requestedScenarioVersion());
                finish(rec, TrialResult.success(rec.taskId, report));
            } catch (DataValidationException e) {
                finish(rec, TrialResult.rejected(rec.taskId, e.reason(), e.getMessage()));
            } catch (Throwable t) {
                finish(rec, TrialResult.failed(rec.taskId, t.toString()));
            }
        });
    }

    private void finish(TaskRecord rec, TrialResult result) {
        List<TaskRecord> toDispatch = new ArrayList<>();
        synchronized (lock) {
            if (isTerminal(rec.status)) {
                log.info("[task-finish] late result ignored: taskId={} status={} lateStatus={}",
                        rec.taskId, rec.status, result.status());
                return;
            }
            rec.status = result.status();
            rec.result = result;
            rec.finishedAt = System.currentTimeMillis();
            countTerminal(result.status());
            running--;
            if (rec.timeoutFuture != null) {
                rec.timeoutFuture.cancel(false);
            }
            log.info("[task-finish] taskId={} RUNNING -> {} reason={} running={}/{}",
                    rec.taskId, result.status(), result.rejectionReason(), running, maxConcurrent);
            toDispatch.addAll(drainQueueLocked());
        }
        toDispatch.forEach(this::dispatch);
    }

    private void onTimeout(TaskRecord rec, long timeoutMs) {
        List<TaskRecord> toDispatch = new ArrayList<>();
        synchronized (lock) {
            if (rec.status != TrialStatus.RUNNING) {
                return;
            }
            rec.status = TrialStatus.TIMED_OUT;
            rec.result = TrialResult.timedOut(rec.taskId, timeoutMs);
            rec.finishedAt = System.currentTimeMillis();
            timedOut.incrementAndGet();
            running--;
            Thread t = rec.workerThread;
            if (t != null) {
                t.interrupt();
            }
            log.info("[task-timeout] taskId={} RUNNING -> TIMED_OUT timeoutMs={} running={}/{}",
                    rec.taskId, timeoutMs, running, maxConcurrent);
            toDispatch.addAll(drainQueueLocked());
        }
        toDispatch.forEach(this::dispatch);
    }

    /** 锁内：把排队任务补进空闲执行槽，返回待派发列表（派发放到锁外）。 */
    private List<TaskRecord> drainQueueLocked() {
        List<TaskRecord> out = new ArrayList<>();
        while (running < maxConcurrent && !queue.isEmpty()) {
            TaskRecord next = queue.poll();
            next.status = TrialStatus.RUNNING;
            next.startedAt = System.currentTimeMillis();
            running++;
            out.add(next);
            log.info("[task-dispatch] taskId={} QUEUED -> RUNNING running={}/{} queued={}/{}",
                    next.taskId, running, maxConcurrent, queue.size(), maxQueue);
        }
        return out;
    }

    private void countTerminal(TrialStatus status) {
        switch (status) {
            case SUCCEEDED -> succeeded.incrementAndGet();
            case REJECTED -> rejected.incrementAndGet();
            case TIMED_OUT -> timedOut.incrementAndGet();
            default -> failed.incrementAndGet();
        }
    }

    private static boolean isTerminal(TrialStatus status) {
        return status == TrialStatus.SUCCEEDED || status == TrialStatus.REJECTED
                || status == TrialStatus.FAILED || status == TrialStatus.TIMED_OUT
                || status == TrialStatus.CANCELLED;
    }

    private TaskSnapshot snapshotOf(TaskRecord rec) {
        synchronized (lock) {
            return new TaskSnapshot(rec.taskId, rec.status, rec.result,
                    rec.submittedAt, rec.startedAt, rec.finishedAt,
                    rec.result != null ? rec.result.detail() : null);
        }
    }

    @PreDestroy
    public void shutdown() {
        workerPool.shutdownNow();
        timeoutScheduler.shutdownNow();
    }

    /** 任务记录：status/result/时间戳等可变字段均只在 lock 保护下读写。 */
    private static final class TaskRecord {
        final String taskId;
        final TrialRequest request;
        final long submittedAt;
        TrialStatus status = TrialStatus.PENDING;
        TrialResult result;
        Long startedAt;
        Long finishedAt;
        volatile Thread workerThread;
        volatile ScheduledFuture<?> timeoutFuture;

        TaskRecord(String taskId, TrialRequest request, long submittedAt) {
            this.taskId = taskId;
            this.request = request;
            this.submittedAt = submittedAt;
        }
    }
}
