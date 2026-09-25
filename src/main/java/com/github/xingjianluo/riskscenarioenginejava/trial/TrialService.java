package com.github.xingjianluo.riskscenarioenginejava.trial;

import com.github.xingjianluo.riskscenarioenginejava.domain.TrialStatus;
import com.github.xingjianluo.riskscenarioenginejava.engine.ScenarioAnalysisService;
import com.github.xingjianluo.riskscenarioenginejava.validation.DataValidationException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 试算编排：
 * - 幂等：相同 idempotencyKey 的并发/重复提交共享同一 Future，只执行一次、只计入一次；
 * - 隔离：每个试算在独立任务中执行，失败/超时通过 Future 传递，不阻塞、不污染其他试算；
 * - 超时：到期未完成则以 TIMED_OUT 收尾，迟到的计算结果不得覆盖（complete 幂等）；
 * - 指标：终态计数在结果 Future 完成回调中恰好累加一次，重试/重复提交不重复计入。
 */
@Service
public class TrialService {

    private static final Logger log = LoggerFactory.getLogger(TrialService.class);
    private static final long DEFAULT_TIMEOUT_MS = 10_000;

    private final ScenarioAnalysisService analysisService;
    private final ExecutorService workerPool;
    private final ScheduledExecutorService timeoutScheduler;

    private final ConcurrentHashMap<String, CompletableFuture<TrialResult>> trials = new ConcurrentHashMap<>();

    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong deduplicated = new AtomicLong();
    private final AtomicLong succeeded = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong timedOut = new AtomicLong();

    @org.springframework.beans.factory.annotation.Autowired
    public TrialService(ScenarioAnalysisService analysisService) {
        this(analysisService, Math.max(2, Runtime.getRuntime().availableProcessors()));
    }

    public TrialService(ScenarioAnalysisService analysisService, int workers) {
        this.analysisService = analysisService;
        this.workerPool = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "trial-worker");
            t.setDaemon(true);
            return t;
        });
        this.timeoutScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "trial-timeout");
            t.setDaemon(true);
            return t;
        });
    }

    /** 提交试算；相同 idempotencyKey 的重复提交返回同一个 Future。 */
    public CompletableFuture<TrialResult> submit(TrialRequest request) {
        if (request == null || request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            CompletableFuture<TrialResult> rejected = new CompletableFuture<>();
            rejected.complete(TrialResult.rejected(null,
                    com.github.xingjianluo.riskscenarioenginejava.domain.RejectionReason.MISSING_FIELD,
                    "idempotencyKey is required"));
            return rejected;
        }
        // computeIfAbsent 的映射函数对每个 key 至多执行一次（分段锁保证），
        // 用 first 标记区分首次提交与重复提交，重复提交只计数、不重复执行。
        java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(false);
        CompletableFuture<TrialResult> future = trials.computeIfAbsent(request.idempotencyKey(), key -> {
            first.set(true);
            return start(key, request);
        });
        if (!first.get()) {
            long n = deduplicated.incrementAndGet();
            log.info("duplicate submission ignored: key={} dedupCount={}", request.idempotencyKey(), n);
        }
        return future;
    }

    private CompletableFuture<TrialResult> start(String key, TrialRequest request) {
        submitted.incrementAndGet();
        CompletableFuture<TrialResult> result = new CompletableFuture<>();
        long timeoutMs = request.timeoutMs() > 0 ? request.timeoutMs() : DEFAULT_TIMEOUT_MS;

        // 超时兜底：先完成者生效，迟到的结果不得覆盖
        timeoutScheduler.schedule(
                () -> result.complete(TrialResult.timedOut(key, timeoutMs)),
                timeoutMs, TimeUnit.MILLISECONDS);

        workerPool.execute(() -> {
            try {
                var report = analysisService.analyze(request.portfolio(), request.snapshots(),
                        request.scenario(), request.requestedScenarioVersion());
                result.complete(TrialResult.success(key, report));
            } catch (DataValidationException e) {
                log.info("trial {} rejected: reason={} detail={}", key, e.reason(), e.getMessage());
                result.complete(TrialResult.rejected(key, e.reason(), e.getMessage()));
            } catch (Throwable t) {
                log.warn("trial {} failed: {}", key, t.toString());
                result.complete(TrialResult.failed(key, t.toString()));
            }
        });

        // 终态指标：每个 key 恰好计入一次
        result.whenComplete((r, err) -> {
            if (err != null) {
                failed.incrementAndGet();
                return;
            }
            switch (r.status()) {
                case SUCCEEDED -> succeeded.incrementAndGet();
                case REJECTED -> rejected.incrementAndGet();
                case TIMED_OUT -> timedOut.incrementAndGet();
                default -> failed.incrementAndGet();
            }
        });
        return result;
    }

    public TrialMetrics metrics() {
        return new TrialMetrics(submitted.get(), deduplicated.get(), succeeded.get(),
                rejected.get(), failed.get(), timedOut.get());
    }

    /** 仅供测试：当前在册试算数。 */
    public int registeredTrials() {
        return trials.size();
    }

    @PreDestroy
    public void shutdown() {
        workerPool.shutdownNow();
        timeoutScheduler.shutdownNow();
    }
}
