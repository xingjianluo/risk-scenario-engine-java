package com.github.xingjianluo.riskscenarioenginejava.trial;

import com.github.xingjianluo.riskscenarioenginejava.domain.RejectionReason;
import com.github.xingjianluo.riskscenarioenginejava.domain.TrialStatus;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 任务化试算层：在 {@link TrialService} 之上提供"提交即返编号、随时查询、可取消、容量约束"的用法。
 *
 * <p>语义约定：
 * <ul>
 *   <li>票据：ticketId 即 idempotencyKey；提交立即返回，不等待计算完成。</li>
 *   <li>查询：同一编号反复查询结果稳定一致；查询不触发任何重复计算。</li>
 *   <li>幂等：相同 key 重复提交返回同一票据的当前快照，不重复占坑、不重复计入容量。</li>
 *   <li>容量：同时执行上限 maxConcurrent，超出排队；队列满（maxQueue）立即以
 *       {@link RejectionReason#CAPACITY_EXCEEDED} 拒绝，与数据类拒绝原因可区分。</li>
 *   <li>取消：QUEUED→CANCELLED 立即释放队列槽位；RUNNING→CANCELLED 中断等待并丢弃迟到结果，
 *       与超时/失败/拒绝明确区分；已终态的取消为幂等空操作，返回当前状态。</li>
 *   <li>终态后同 key 再提交仍返回该终态快照（确定且可追溯）；如需重算请使用新的 idempotencyKey。</li>
 *   <li>指标自洽：accepted = queuedNow + runningNow + completed + cancelled；
 *       capacityRejected 从未占坑，单独计数。</li>
 * </ul>
 */
@Service
public class TrialTaskService {

    private static final Logger log = LoggerFactory.getLogger(TrialTaskService.class);
    private static final int DEFAULT_MAX_QUEUE = 100;

    /** 任务生命周期：QUEUED/RUNNING 为进行中，DONE/CANCELLED 为终态。 */
    private enum Phase { QUEUED, RUNNING, DONE, CANCELLED }

    private static final class TrialTask {
        final String ticketId;
        final TrialRequest request;
        final AtomicReference<Phase> phase = new AtomicReference<>(Phase.QUEUED);
        volatile TrialResult result;   // DONE 时的终态结果
        volatile Future<?> worker;     // 执行句柄，用于取消 queued/running 任务

        TrialTask(String ticketId, TrialRequest request) {
            this.ticketId = ticketId;
            this.request = request;
        }
    }

    private final TrialService trialService;
    private final ThreadPoolExecutor executor;
    private final int maxConcurrent;
    private final int maxQueue;

    private final ConcurrentHashMap<String, TrialTask> tasks = new ConcurrentHashMap<>();

    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong deduplicated = new AtomicLong();
    private final AtomicLong capacityRejected = new AtomicLong();
    private final AtomicLong cancelled = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong queuedNow = new AtomicLong();
    private final AtomicLong runningNow = new AtomicLong();

    @org.springframework.beans.factory.annotation.Autowired
    public TrialTaskService(TrialService trialService) {
        this(trialService, Math.max(2, Runtime.getRuntime().availableProcessors()), DEFAULT_MAX_QUEUE);
    }

    public TrialTaskService(TrialService trialService, int maxConcurrent, int maxQueue) {
        this.trialService = trialService;
        this.maxConcurrent = maxConcurrent;
        this.maxQueue = maxQueue;
        this.executor = new ThreadPoolExecutor(maxConcurrent, maxConcurrent,
                0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(maxQueue), r -> {
            Thread t = new Thread(r, "trial-task-worker");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 提交试算并立即返回票据快照。相同 idempotencyKey 的重复提交返回同一票据的当前快照，
     * 不重复占容量、不重复执行。
     */
    public TrialTaskView submit(TrialRequest request) {
        if (request == null || request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            log.info("[task-submit] rejected: reason={} detail=idempotencyKey is required",
                    RejectionReason.MISSING_FIELD);
            TrialResult r = TrialResult.rejected(null, RejectionReason.MISSING_FIELD,
                    "idempotencyKey is required");
            return new TrialTaskView(null, TrialStatus.REJECTED, r, r.detail());
        }
        String key = request.idempotencyKey();
        AtomicLongMarker first = new AtomicLongMarker();
        TrialTask task = tasks.computeIfAbsent(key, k -> {
            first.mark();
            return admit(k, request);
        });
        if (!first.marked) {
            long n = deduplicated.incrementAndGet();
            log.info("[task-submit] duplicate ignored: ticket={} dedupCount={} currentStatus={}",
                    key, n, viewOf(task).status());
        }
        return viewOf(task);
    }

    /** 准入：占坑入队；队列满则以 CAPACITY_EXCEEDED 拒绝（记录可查询，但不计入 accepted）。 */
    private TrialTask admit(String key, TrialRequest request) {
        TrialTask task = new TrialTask(key, request);
        try {
            task.worker = executor.submit(() -> run(task));
            accepted.incrementAndGet();
            queuedNow.incrementAndGet();
            log.info("[task-submit] accepted: ticket={} portfolio={} positions={} snapshots={} "
                            + "scenario={} version={} timeoutMs={} queuedNow={}",
                    key,
                    request.portfolio() == null ? null : request.portfolio().portfolioId(),
                    request.portfolio() == null ? 0 : request.portfolio().positions().size(),
                    request.snapshots() == null ? 0 : request.snapshots().size(),
                    request.scenario() == null ? null : request.scenario().scenarioId(),
                    request.requestedScenarioVersion(), request.timeoutMs(), queuedNow.get());
        } catch (RuntimeException e) { // RejectedExecutionException：队列已满
            task.result = TrialResult.rejected(key, RejectionReason.CAPACITY_EXCEEDED,
                    "capacity exceeded: maxConcurrent=" + maxConcurrent + " maxQueue=" + maxQueue);
            task.phase.set(Phase.DONE);
            long n = capacityRejected.incrementAndGet();
            log.info("[task-submit] rejected: ticket={} reason={} detail={} capacityRejectedCount={}",
                    key, RejectionReason.CAPACITY_EXCEEDED, task.result.detail(), n);
        }
        return task;
    }

    private void run(TrialTask task) {
        if (!task.phase.compareAndSet(Phase.QUEUED, Phase.RUNNING)) {
            return; // 排队期间被取消：直接退出，不占用任何计算资源
        }
        queuedNow.decrementAndGet();
        runningNow.incrementAndGet();
        log.info("[task-run] status change: ticket={} QUEUED -> RUNNING runningNow={}",
                task.ticketId, runningNow.get());
        try {
            TrialResult r = trialService.submit(task.request).get();
            complete(task, r);
        } catch (InterruptedException ie) {
            // 运行中被取消：cancel() 已完成状态迁移与计数，此处仅退出
            Thread.currentThread().interrupt();
        } catch (ExecutionException ee) {
            complete(task, TrialResult.failed(task.ticketId, String.valueOf(ee.getCause())));
        } finally {
            runningNow.decrementAndGet();
        }
    }

    /** 计算结束：仅当仍处于 RUNNING 时生效；已被取消的任务丢弃迟到结果。 */
    private void complete(TrialTask task, TrialResult r) {
        task.result = r;
        if (task.phase.compareAndSet(Phase.RUNNING, Phase.DONE)) {
            completed.incrementAndGet();
            log.info("[task-run] status change: ticket={} RUNNING -> {}{}{}",
                    task.ticketId, r.status(),
                    r.rejectionReason() == null ? "" : " reason=" + r.rejectionReason(),
                    r.detail() == null ? "" : " detail=" + r.detail());
        } else {
            log.info("[task-run] late result discarded: ticket={} already CANCELLED, would-be status={}",
                    task.ticketId, r.status());
        }
    }

    /** 查询票据当前快照；未知编号返回 status=null 的可识别快照。查询不触发任何计算。 */
    public TrialTaskView query(String ticketId) {
        TrialTask task = ticketId == null ? null : tasks.get(ticketId);
        if (task == null) {
            return TrialTaskView.notFound(ticketId);
        }
        return viewOf(task);
    }

    /**
     * 取消未完成的试算。已终态（含已取消）时为幂等空操作，返回当前状态；
     * 未知编号返回 status=null 的快照。
     */
    public TrialTaskView cancel(String ticketId) {
        TrialTask task = ticketId == null ? null : tasks.get(ticketId);
        if (task == null) {
            log.info("[task-cancel] unknown ticket={}", ticketId);
            return TrialTaskView.notFound(ticketId);
        }
        for (;;) {
            Phase p = task.phase.get();
            switch (p) {
                case QUEUED -> {
                    if (task.phase.compareAndSet(Phase.QUEUED, Phase.CANCELLED)) {
                        Future<?> w = task.worker;
                        if (w != null) {
                            w.cancel(false); // 不中断（尚未开始），从队列移除
                        }
                        executor.purge(); // 立即释放队列槽位
                        queuedNow.decrementAndGet();
                        long n = cancelled.incrementAndGet();
                        log.info("[task-cancel] status change: ticket={} QUEUED -> CANCELLED "
                                + "queuedNow={} cancelledCount={}", ticketId, queuedNow.get(), n);
                        return viewOf(task);
                    }
                }
                case RUNNING -> {
                    if (task.phase.compareAndSet(Phase.RUNNING, Phase.CANCELLED)) {
                        Future<?> w = task.worker;
                        if (w != null) {
                            w.cancel(true); // 中断等待中的工作线程，迟到结果由 complete() 丢弃
                        }
                        long n = cancelled.incrementAndGet();
                        log.info("[task-cancel] status change: ticket={} RUNNING -> CANCELLED "
                                + "cancelledCount={}", ticketId, n);
                        return viewOf(task);
                    }
                }
                default -> {
                    log.info("[task-cancel] no-op: ticket={} already terminal status={}",
                            ticketId, viewOf(task).status());
                    return viewOf(task); // 终态：幂等空操作
                }
            }
        }
    }

    private TrialTaskView viewOf(TrialTask task) {
        return switch (task.phase.get()) {
            case QUEUED -> new TrialTaskView(task.ticketId, TrialStatus.QUEUED, null, "queued");
            case RUNNING -> new TrialTaskView(task.ticketId, TrialStatus.RUNNING, null, "running");
            case CANCELLED -> new TrialTaskView(task.ticketId, TrialStatus.CANCELLED, null, "cancelled");
            case DONE -> {
                TrialResult r = task.result;
                yield new TrialTaskView(task.ticketId, r.status(), r, r.detail());
            }
        };
    }

    /** 任务层指标快照；不变式：accepted = queuedNow + runningNow + completed + cancelled。 */
    public TaskMetrics metrics() {
        return new TaskMetrics(accepted.get(), deduplicated.get(), capacityRejected.get(),
                cancelled.get(), completed.get(), queuedNow.get(), runningNow.get());
    }

    /** 仅供测试：在册票据数。 */
    public int registeredTasks() {
        return tasks.size();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /** computeIfAbsent 映射函数内的"首次"标记。 */
    private static final class AtomicLongMarker {
        boolean marked;

        void mark() {
            marked = true;
        }
    }
}
