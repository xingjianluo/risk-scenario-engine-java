package com.github.xingjianluo.riskscenarioenginejava.tests;

import com.github.xingjianluo.riskscenarioenginejava.model.MarketSnapshot;
import com.github.xingjianluo.riskscenarioenginejava.model.Portfolio;
import com.github.xingjianluo.riskscenarioenginejava.model.ResultStatus;
import com.github.xingjianluo.riskscenarioenginejava.model.RiskResult;
import com.github.xingjianluo.riskscenarioenginejava.model.ScenarioDefinition;
import com.github.xingjianluo.riskscenarioenginejava.model.TrialRequest;
import com.github.xingjianluo.riskscenarioenginejava.service.RiskCalculationService;
import com.github.xingjianluo.riskscenarioenginejava.service.TrialExecutionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 并发执行语义：幂等去重、隔离、超时、失败重试与样本计数一致性。 */
class TrialExecutionServiceTest {

    private static final Logger LOG = Logger.getLogger(TrialExecutionServiceTest.class.getName());

    private final List<TrialExecutionService> services = new ArrayList<>();

    private TrialExecutionService serviceWith(RiskCalculationService calc) {
        TrialExecutionService service = new TrialExecutionService(calc);
        services.add(service);
        return service;
    }

    @AfterEach
    void tearDown() {
        services.forEach(TrialExecutionService::shutdown);
    }

    private TrialRequest validRequest(String id, Integer timeout, Integer maxAttempts) {
        Portfolio portfolio = TestData.portfolio("PF", 100, 50);
        List<MarketSnapshot> snapshots = TestData.snapshots(5, 100, 20, 0.2, 0.05);
        ScenarioDefinition scenario = TestData.shockScenario("S1", TestData.V_SHOCK, -0.1, 0.02);
        return new TrialRequest(id, portfolio, snapshots, scenario, timeout, maxAttempts);
    }

    private RiskCalculationService alwaysOk() {
        return new RiskCalculationService(null, null, null) {
            @Override
            public RiskResult calculate(TrialRequest request, int attempt) {
                return new RiskResult(request.requestId(), "PF", "S1", TestData.V_SHOCK,
                        ResultStatus.ACCEPTED, null, null,
                        11000d, -110d, 200d, 260d, 300d, 340d,
                        1, "digest-" + request.requestId(), List.of("mock 判定依据"),
                        Instant.now().toEpochMilli(), attempt);
            }
        };
    }

    @Test
    @DisplayName("并发重复提交同一requestId：只计算一次，所有调用方拿到同一结果（样本计数不重复计入）")
    void concurrentDuplicateSubmissionsComputeOnce() throws Exception {
        AtomicInteger calculations = new AtomicInteger();
        CountDownLatch inside = new CountDownLatch(1);
        RiskCalculationService calc = new RiskCalculationService(null, null, null) {
            @Override
            public RiskResult calculate(TrialRequest request, int attempt) {
                calculations.incrementAndGet();
                try {
                    inside.await(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new RiskResult(request.requestId(), "PF", "S1", TestData.V_SHOCK,
                        ResultStatus.ACCEPTED, null, null, 11000d, -110d,
                        200d, 260d, 300d, 340d, 1, "digest-X", List.of(),
                        Instant.now().toEpochMilli(), attempt);
            }
        };
        TrialExecutionService service = serviceWith(calc);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<CompletableFuture<RiskResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return service.submit(validRequest("DUP-1", null, null)).join();
            }, pool).whenComplete((r, e) -> done.countDown()));
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "并发提交应全部返回");
        inside.countDown();

        RiskResult first = futures.get(0).join();
        for (CompletableFuture<RiskResult> f : futures) {
            assertSame(first, f.join(), "重复提交必须返回同一结论实例");
            assertEquals(1, f.join().sampleCount(), "样本计数必须一致，不得重复计入");
        }
        assertEquals(1, calculations.get(), "相同 requestId 只允许实际计算一次");
        pool.shutdownNow();
        LOG.info("输入摘要: 16线程并发提交requestId=DUP-1, 场景版本=" + TestData.V_SHOCK
                + "; 判定依据: computeIfAbsent幂等; 实际计算次数=" + calculations.get());
    }

    @Test
    @DisplayName("顺序重复提交同一requestId：第二次直接复用缓存，不再次计算")
    void sequentialDuplicateUsesCache() {
        AtomicInteger calculations = new AtomicInteger();
        TrialExecutionService service = serviceWith(new RiskCalculationService(null, null, null) {
            @Override
            public RiskResult calculate(TrialRequest request, int attempt) {
                calculations.incrementAndGet();
                return alwaysOk().calculate(request, attempt);
            }
        });
        RiskResult r1 = service.submit(validRequest("DUP-2", null, null)).join();
        RiskResult r2 = service.submit(validRequest("DUP-2", null, null)).join();
        assertSame(r1, r2);
        assertEquals(1, calculations.get());
        assertNotNull(service.getIfPresent("DUP-2"));
        LOG.info("判定依据: 确定结论缓存复用; 实际计算次数=" + calculations.get());
    }

    @Test
    @DisplayName("单个长耗时试算超时：自身FAILED，不阻塞同一批其他试算")
    void timeoutDoesNotBlockOthers() {
        AtomicInteger slowAttempts = new AtomicInteger();
        RiskCalculationService calc = new RiskCalculationService(null, null, null) {
            @Override
            public RiskResult calculate(TrialRequest request, int attempt) {
                if (!"SLOW".equals(request.requestId())) {
                    return alwaysOk().calculate(request, attempt);
                }
                slowAttempts.incrementAndGet();
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CompletionException("慢任务被超时中断", e);
                }
                return alwaysOk().calculate(request, attempt);
            }
        };
        TrialExecutionService service = serviceWith(calc);

        TrialRequest slow = validRequest("SLOW", 100, 2);
        TrialRequest fast = validRequest("FAST", 5_000, 1);

        long begin = System.currentTimeMillis();
        List<RiskResult> results = service.submitAll(List.of(slow, fast));
        long elapsed = System.currentTimeMillis() - begin;

        assertEquals(ResultStatus.FAILED, results.get(0).status());
        assertTrue(results.get(0).rejectReason().contains("TimeoutException"),
                "超时原因必须可区分: " + results.get(0).rejectReason());
        assertEquals(ResultStatus.ACCEPTED, results.get(1).status(),
                "快速试算不得被长耗时试算阻塞或污染");
        assertEquals(2, results.get(0).attempt(), "超时按maxAttempts重试两次");
        assertEquals(2, slowAttempts.get(), "两次尝试均实际发生");
        assertTrue(elapsed < 3_000, "批量总耗时应接近2次超时，实际=" + elapsed);
        LOG.info("输入摘要: SLOW(100ms超时,2次) + FAST; 场景版本=" + TestData.V_SHOCK
                + "; 判定依据: Future+get(timeout)精确中断，cached线程池避免嵌套死锁; 拒绝/失败原因=" + results.get(0).rejectReason());
    }

    @Test
    @DisplayName("技术异常试算FAILED，不影响其他试算；FAILED后允许重新提交并成功")
    void failureIsolatedAndRetryable() {
        AtomicInteger calls = new AtomicInteger();
        RiskCalculationService flaky = new RiskCalculationService(null, null, null) {
            @Override
            public RiskResult calculate(TrialRequest request, int attempt) {
                if (request.requestId().equals("BROKEN")) {
                    calls.incrementAndGet();
                    throw new IllegalStateException("模拟计算节点不可用");
                }
                return alwaysOk().calculate(request, attempt);
            }
        };
        TrialExecutionService service = serviceWith(flaky);

        List<RiskResult> results = service.submitAll(List.of(
                validRequest("BROKEN", null, 1), validRequest("OK", null, 1)));
        assertEquals(ResultStatus.FAILED, results.get(0).status());
        assertTrue(results.get(0).rejectReason().contains("IllegalStateException"));
        assertEquals(ResultStatus.ACCEPTED, results.get(1).status());

        // FAILED 不缓存为确定结论，节点恢复后同 requestId 可重新提交
        TrialExecutionService service2 = serviceWith(alwaysOk());
        RiskResult recovered = service2.submit(validRequest("BROKEN", null, 1)).join();
        assertEquals(ResultStatus.ACCEPTED, recovered.status());
        LOG.info("输入摘要: BROKEN(抛异常,1次尝试)+OK; 判定依据: 异常仅标记FAILED; 失败原因="
                + results.get(0).rejectReason());
    }

    @Test
    @DisplayName("部分失败不重复计入：混合批次中成功结果指标与样本计数保持原值")
    void partialFailureKeepsCountsConsistent() {
        AtomicInteger okCalls = new AtomicInteger();
        RiskCalculationService mixed = new RiskCalculationService(null, null, null) {
            @Override
            public RiskResult calculate(TrialRequest request, int attempt) {
                if (request.requestId().startsWith("FAIL")) {
                    throw new RuntimeException("boom");
                }
                okCalls.incrementAndGet();
                return alwaysOk().calculate(request, attempt);
            }
        };
        TrialExecutionService service = serviceWith(mixed);
        List<TrialRequest> requests = List.of(
                validRequest("OK-1", null, 1),
                validRequest("FAIL-1", null, 1),
                validRequest("OK-2", null, 1),
                validRequest("FAIL-2", null, 1));
        List<RiskResult> results = service.submitAll(requests);
        assertEquals(ResultStatus.ACCEPTED, results.get(0).status());
        assertEquals(ResultStatus.FAILED, results.get(1).status());
        assertEquals(ResultStatus.ACCEPTED, results.get(2).status());
        assertEquals(ResultStatus.FAILED, results.get(3).status());
        assertEquals(1, results.get(0).sampleCount());
        assertEquals(1, results.get(2).sampleCount());
        assertEquals(0, results.get(1).sampleCount());
        assertEquals(2, okCalls.get(), "只有2个成功试算被实际计算");
        LOG.info("判定依据: 4个混合试算2成功2失败; 成功样本计数均=1, 失败=0; 不重复计入");
    }

    @Test
    @DisplayName("重试成功时 attempt 透传最终尝试次数，指标只反映最后一次成功计算")
    void retrySucceedsAndAttemptRecorded() {
        AtomicInteger calls = new AtomicInteger();
        RiskCalculationService recoverAfterOne = new RiskCalculationService(null, null, null) {
            @Override
            public RiskResult calculate(TrialRequest request, int attempt) {
                calls.incrementAndGet();
                if (attempt == 1) {
                    throw new RuntimeException("首次瞬时故障");
                }
                return alwaysOk().calculate(request, attempt);
            }
        };
        TrialExecutionService service = serviceWith(recoverAfterOne);
        RiskResult result = service.submit(validRequest("RETRY-OK", null, 3)).join();
        assertEquals(ResultStatus.ACCEPTED, result.status());
        assertEquals(2, result.attempt(), "第二次尝试成功，attempt必须记录为2");
        assertEquals(2, calls.get());
        assertEquals("digest-RETRY-OK", result.inputDigest());
        LOG.info("判定依据: 第1次抛异常->第2次成功; attempt=" + result.attempt()
                + " 指标与样本计数来自唯一一次成功计算");
    }

    @Test
    @DisplayName("缺少requestId：直接FAILED，且不进入幂等缓存")
    void missingRequestIdFailedFast() {
        TrialExecutionService service = serviceWith(alwaysOk());
        RiskResult result = service.submit(
                new TrialRequest(null, TestData.portfolio("PF", 1, 1),
                        TestData.snapshots(2, 100, 20, 0.1, 0.1),
                        TestData.shockScenario("S1", TestData.V_SHOCK, -0.1, -0.1),
                        null, null)).join();
        assertEquals(ResultStatus.FAILED, result.status());
        assertTrue(result.rejectReason().contains("requestId"));
        LOG.info("判定依据: requestId缺失无法幂等; 失败原因=" + result.rejectReason());
    }

    @Test
    @DisplayName("线程池饱和回归：多个并发试算（含即时拒绝）均快速完成，无嵌套调度死锁")
    void noPoolDeadlockUnderConcurrency() {
        AtomicInteger calculations = new AtomicInteger();
        RiskCalculationService calc = new RiskCalculationService(null, null, null) {
            @Override
            public RiskResult calculate(TrialRequest request, int attempt) {
                calculations.incrementAndGet();
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return alwaysOk().calculate(request, attempt);
            }
        };
        TrialExecutionService service = serviceWith(calc);
        List<TrialRequest> requests = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            requests.add(validRequest("CONC-" + i, 2_000, 1));
        }
        long begin = System.currentTimeMillis();
        List<RiskResult> results = service.submitAll(requests);
        long elapsed = System.currentTimeMillis() - begin;
        assertEquals(24, results.size());
        for (RiskResult r : results) {
            assertEquals(ResultStatus.ACCEPTED, r.status(),
                    "不得因线程池嵌套调度死锁导致超时FAILED: " + r.rejectReason());
        }
        assertEquals(24, calculations.get(), "24个不同requestId必须各实际计算一次");
        assertTrue(elapsed < 5_000, "24个200ms试算并发完成，不应接近超时上限，实际=" + elapsed);
        LOG.info("输入摘要: 24个并发试算各200ms; 判定依据: cached线程池避免嵌套死锁; 总耗时=" + elapsed + "ms");
    }
}
