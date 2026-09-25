package com.github.xingjianluo.riskscenarioenginejava.service;

import com.github.xingjianluo.riskscenarioenginejava.model.ResultStatus;
import com.github.xingjianluo.riskscenarioenginejava.model.RiskResult;
import com.github.xingjianluo.riskscenarioenginejava.model.TrialRequest;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 并发试算执行服务。
 *
 * <p>语义约定：</p>
 * <ul>
 *   <li><b>隔离</b>：每个试算在线程池中独立运行；单个试算长耗时（超时）、技术异常，
 *       只在自身结果上体现 FAILED，不阻塞也不污染其他试算；</li>
 *   <li><b>幂等</b>：以 requestId 为键去重；相同 requestId 的重复提交（含并发同时提交）
 *       共享同一个 {@link CompletableFuture}，指标与样本计数只计算一次，绝不重复计入；</li>
 *   <li><b>重试</b>：仅技术失败（异常/超时）在同一试算内重试，至多 maxAttempts 次，
 *       最终结果记录实际尝试次数 attempt；REJECTED/ACCEPTED 是确定结论，不重试；</li>
 *   <li><b>可追溯</b>：成功/拒绝结果携带输入摘要 inputDigest 与场景版本。</li>
 * </ul>
 */
@Service
public class TrialExecutionService {

    private static final Logger LOG = Logger.getLogger(TrialExecutionService.class.getName());

    static final int DEFAULT_TIMEOUT_MS = 5_000;
    static final int DEFAULT_MAX_ATTEMPTS = 2;

    private final RiskCalculationService calculationService;
    private final ExecutorService executor;

    /** requestId -> 进行中或已完成的试算；去重的唯一入口。 */
    private final ConcurrentHashMap<String, CompletableFuture<RiskResult>> inflight =
            new ConcurrentHashMap<>();

    public TrialExecutionService(RiskCalculationService calculationService) {
        this.calculationService = calculationService;
        // 使用弹性线程池而非固定池：外层 worker（runWithRetry）会把单次计算提交到同一执行器
        // 并 join 等待，固定池在并发占满时会出现“池中线程等待池中永远无法被调度的任务”的死锁；
        // cached 池保证嵌套提交的计算任务可立即获得线程。线程均为 daemon，JVM 退出时不会滞留。
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "risk-trial-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public CompletableFuture<RiskResult> submit(TrialRequest request) {
        if (request == null || isBlank(request.requestId())) {
            return CompletableFuture.completedFuture(
                    failed(request, new IllegalArgumentException("requestId 缺失，无法幂等提交"), 1));
        }
        // computeIfAbsent 保证并发重复提交只注册一个计算任务。
        // 注意：清理回调必须在 computeIfAbsent 之外注册，否则回调可能在同一线程内联执行，
        // 对同一 map 做 put/remove 会触发 ConcurrentHashMap "Recursive update"。
        final String id = request.requestId();
        CompletableFuture<RiskResult> registered = inflight.computeIfAbsent(id,
                key -> CompletableFuture.supplyAsync(() -> runWithRetry(request), executor));
        registered.whenComplete((r, e) -> {
            if (r != null && r.status() == ResultStatus.FAILED) {
                // 技术失败不是确定结论：移除缓存，允许后续以相同 requestId 重新提交
                inflight.remove(id, registered);
            }
            // ACCEPTED / REJECTED 是确定结论：保留原 Future 供重复提交与查询幂等复用
        });
        return registered;
    }

    /**
     * 批量提交：所有试算并发执行、互不阻塞；返回顺序与请求顺序一致。
     */
    public List<RiskResult> submitAll(List<TrialRequest> requests) {
        if (requests == null) {
            return List.of();
        }
        List<CompletableFuture<RiskResult>> futures = new ArrayList<>(requests.size());
        for (TrialRequest request : requests) {
            futures.add(submit(request));
        }
        List<RiskResult> results = new ArrayList<>(requests.size());
        for (int i = 0; i < futures.size(); i++) {
            TrialRequest request = requests.get(i);
            int maxAttempts = resolveMaxAttempts(request);
            try {
                results.add(futures.get(i).join());
            } catch (CompletionException e) {
                // 正常情况下 runWithRetry 已兜住所有异常，这里是防御性处理
                results.add(failed(request, new TimeoutException("批量等待异常: " + e.getMessage()),
                        maxAttempts));
            }
        }
        return results;
    }

    /** 查询已提交试算的结果；不存在时返回 null。 */
    public RiskResult getIfPresent(String requestId) {
        CompletableFuture<RiskResult> future = inflight.get(requestId);
        if (future == null || !future.isDone()) {
            return null;
        }
        try {
            return future.getNow(null);
        } catch (Exception e) {
            return null;
        }
    }

    private RiskResult runWithRetry(TrialRequest request) {
        int timeoutMs = resolveTimeout(request);
        int maxAttempts = resolveMaxAttempts(request);
        Exception lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            final int attemptNumber = attempt;
            // 不使用 CompletableFuture.orTimeout：其超时由 JDK 调度线程触发 cancel，
            // 中断信号落在调度线程上，无法可靠中断 supplyAsync 的实际计算线程
            // （实测超时后旧慢任务仍继续运行，造成重复计入）。
            // 改用 Future + get(timeout)，超时后 future.cancel(true) 精确中断计算线程。
            java.util.concurrent.Future<RiskResult> calcFuture =
                    executor.submit(() -> calculationService.calculate(request, attemptNumber));
            try {
                RiskResult result = calcFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
                if (attemptNumber > 1) {
                    LOG.info(() -> "[TRIAL-RETRY-OK] requestId=" + request.requestId()
                            + " 第 " + attemptNumber + " 次尝试成功");
                }
                return result;
            } catch (TimeoutException e) {
                calcFuture.cancel(true);
                lastError = e;
                LOG.log(Level.WARNING, "[TRIAL-TIMEOUT] requestId=" + request.requestId()
                        + " 第 " + attemptNumber + "/" + maxAttempts
                        + " 次尝试超时（" + timeoutMs + "ms），已中断底层计算");
            } catch (InterruptedException e) {
                calcFuture.cancel(true);
                Thread.currentThread().interrupt();
                lastError = e;
                break;
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                lastError = cause instanceof Exception ex ? ex : new Exception(cause);
                final int tried = attemptNumber;
                LOG.log(Level.WARNING, "[TRIAL-ATTEMPT] requestId=" + request.requestId()
                        + " 第 " + tried + "/" + maxAttempts + " 次尝试技术失败: "
                        + cause.getClass().getSimpleName() + " - " + cause.getMessage());
            }
        }
        return failed(request, lastError, maxAttempts);
    }

    private RiskResult failed(TrialRequest request, Exception error, int attempts) {
        String requestId = request == null || isBlank(request.requestId())
                ? "(anonymous)" : request.requestId();
        String portfolioId = request != null && request.portfolio() != null
                ? request.portfolio().portfolioId() : null;
        String scenarioId = request != null && request.scenario() != null
                ? request.scenario().scenarioId() : null;
        String version = request != null && request.scenario() != null
                ? request.scenario().version() : null;
        String reason = error == null ? "未知技术失败"
                : error.getClass().getSimpleName() + ": " + error.getMessage();
        LOG.warning("[TRIAL-FAILED] requestId=" + requestId + " 已尝试 " + attempts
                + " 次，结论 FAILED（可重新提交）：" + reason);
        return new RiskResult(requestId, portfolioId, scenarioId, version,
                ResultStatus.FAILED, null, reason,
                0d, 0d, 0d, 0d, 0d, 0d, 0, null, List.of(),
                Instant.now().toEpochMilli(), attempts);
    }

    private int resolveTimeout(TrialRequest request) {
        if (request != null && request.timeoutMs() != null && request.timeoutMs() > 0) {
            return request.timeoutMs();
        }
        return DEFAULT_TIMEOUT_MS;
    }

    private int resolveMaxAttempts(TrialRequest request) {
        if (request != null && request.maxAttempts() != null && request.maxAttempts() >= 1) {
            return request.maxAttempts();
        }
        return DEFAULT_MAX_ATTEMPTS;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
