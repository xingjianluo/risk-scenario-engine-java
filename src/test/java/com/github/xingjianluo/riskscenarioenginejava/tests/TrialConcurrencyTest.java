package com.github.xingjianluo.riskscenarioenginejava.tests;

import com.github.xingjianluo.riskscenarioenginejava.domain.*;
import com.github.xingjianluo.riskscenarioenginejava.engine.RiskMetricsCalculator;
import com.github.xingjianluo.riskscenarioenginejava.engine.ScenarioAnalysisService;
import com.github.xingjianluo.riskscenarioenginejava.engine.ValuationEngine;
import com.github.xingjianluo.riskscenarioenginejava.trial.TrialMetrics;
import com.github.xingjianluo.riskscenarioenginejava.trial.TrialRequest;
import com.github.xingjianluo.riskscenarioenginejava.trial.TrialResult;
import com.github.xingjianluo.riskscenarioenginejava.trial.TrialService;
import com.github.xingjianluo.riskscenarioenginejava.validation.InputValidator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发重复提交、失败/超时隔离、指标一致性。
 */
class TrialConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(TrialConcurrencyTest.class);

    private final ScenarioAnalysisService analysisService =
            new ScenarioAnalysisService(new InputValidator(), new ValuationEngine(), new RiskMetricsCalculator());

    private TrialRequest request(String key) {
        return new TrialRequest(key, TestFixtures.portfolio(), TestFixtures.snapshots(10),
                TestFixtures.scenario("v1"), "v1", 5_000);
    }

    @Test
    void concurrentDuplicateSubmissionsExecuteOnce() throws Exception {
        TrialService service = new TrialService(analysisService, 4);
        int threads = 16;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<TrialResult>> futures = new ArrayList<>();
        List<Thread> pool = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                synchronized (futures) {
                    futures.add(service.submit(request("dup-key")));
                }
            });
            pool.add(t);
            t.start();
        }
        ready.await();
        go.countDown();
        for (Thread t : pool) {
            t.join();
        }

        TrialResult first = futures.get(0).get(5, TimeUnit.SECONDS);
        for (CompletableFuture<TrialResult> f : futures) {
            assertSame(futures.get(0), f, "相同 key 必须返回同一 Future");
            assertEquals(TrialStatus.SUCCEEDED, f.get().status());
        }
        TrialMetrics m = service.metrics();
        log.info("[dup-submit] key=dup-key status={} metrics={} registered={}",
                first.status(), m.asMap(), service.registeredTrials());
        assertEquals(1, m.submitted(), "同一 key 只执行一次");
        assertEquals(threads - 1, m.deduplicated(), "其余提交全部去重");
        assertEquals(1, m.succeeded(), "成功计数不得重复");
        service.shutdown();
    }

    @Test
    void retryAfterRejectionDoesNotDoubleCount() {
        TrialService service = new TrialService(analysisService, 2);
        // 非法场景：冲击未持有资产 -> REJECTED
        ScenarioDefinition bad = new ScenarioDefinition("SC-BAD", "v1",
                java.util.Map.of("NOPE", -0.1), null, 0, 1);
        TrialRequest badReq = new TrialRequest("bad-key", TestFixtures.portfolio(),
                TestFixtures.snapshots(5), bad, "v1", 5_000);

        TrialResult r1 = service.submit(badReq).join();
        TrialResult r2 = service.submit(badReq).join(); // 重试同一 key -> 去重
        log.info("[retry] status={} reason={} detail={}", r1.status(), r1.rejectionReason(), r1.detail());
        assertEquals(TrialStatus.REJECTED, r1.status());
        assertEquals(RejectionReason.UNKNOWN_ASSET, r1.rejectionReason());
        assertSame(r1, r2);
        TrialMetrics m = service.metrics();
        log.info("[retry] metrics={}", m.asMap());
        assertEquals(1, m.submitted());
        assertEquals(1, m.rejected());
        assertEquals(1, m.deduplicated());
        service.shutdown();
    }

    @Test
    void failingTrialDoesNotBlockOthers() {
        TrialService service = new TrialService(analysisService, 4);
        ScenarioDefinition bad = new ScenarioDefinition("SC-BAD", "v1",
                java.util.Map.of("NOPE", -0.1), null, 0, 1);
        CompletableFuture<TrialResult> badFuture = service.submit(new TrialRequest(
                "bad", TestFixtures.portfolio(), TestFixtures.snapshots(5), bad, "v1", 5_000));
        List<CompletableFuture<TrialResult>> good = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            good.add(service.submit(request("good-" + i)));
        }
        assertEquals(TrialStatus.REJECTED, badFuture.join().status());
        for (CompletableFuture<TrialResult> f : good) {
            assertEquals(TrialStatus.SUCCEEDED, f.join().status(), "失败试算不得阻塞其他试算");
        }
        TrialMetrics m = service.metrics();
        log.info("[failure-isolation] metrics={}", m.asMap());
        assertEquals(7, m.submitted());
        assertEquals(6, m.succeeded());
        assertEquals(1, m.rejected());
        service.shutdown();
    }

    @Test
    void slowTrialTimesOutWithoutPollutingOthers() throws Exception {
        // 慢试算：analyze 休眠 3s，超时 200ms
        ScenarioAnalysisService slow = new ScenarioAnalysisService(
                new InputValidator(), new ValuationEngine(), new RiskMetricsCalculator()) {
            @Override
            public RiskReport analyze(Portfolio portfolio, List<MarketSnapshot> snapshots,
                                      ScenarioDefinition scenario, String requestedVersion) {
                try {
                    Thread.sleep(3_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.analyze(portfolio, snapshots, scenario, requestedVersion);
            }
        };
        TrialService service = new TrialService(slow, 2);

        CompletableFuture<TrialResult> slowFuture = service.submit(new TrialRequest(
                "slow", TestFixtures.portfolio(), TestFixtures.snapshots(10),
                TestFixtures.scenario("v1"), "v1", 200));
        TrialResult slowResult = slowFuture.get(5, TimeUnit.SECONDS);
        log.info("[timeout] slow trial status={} detail={}", slowResult.status(), slowResult.detail());
        assertEquals(TrialStatus.TIMED_OUT, slowResult.status());

        // 等待慢任务真正结束后，迟到结果不得覆盖 TIMED_OUT，指标也不得重复计入
        Thread.sleep(3_500);
        assertEquals(TrialStatus.TIMED_OUT, service.submit(request("slow")).join().status());
        // 另一个正常 key（超时 5s > 3s 休眠）应成功，不受慢试算影响
        TrialResult fast = service.submit(new TrialRequest("fast", TestFixtures.portfolio(),
                TestFixtures.snapshots(10), TestFixtures.scenario("v1"), "v1", 5_000))
                .get(6, TimeUnit.SECONDS);
        assertEquals(TrialStatus.SUCCEEDED, fast.status());
        TrialMetrics m = service.metrics();
        log.info("[timeout] metrics after late completion={}", m.asMap());
        assertEquals(2, m.submitted()); // slow + fast
        assertEquals(1, m.deduplicated()); // slow 的重复提交
        assertEquals(1, m.timedOut());
        assertEquals(1, m.succeeded());
        service.shutdown();
    }

    @Test
    void metricsConsistentUnderMixedConcurrentLoad() throws Exception {
        TrialService service = new TrialService(analysisService, 8);
        int keys = 40;
        int dupPerKey = 3;
        AtomicInteger submitted = new AtomicInteger();
        List<CompletableFuture<TrialResult>> all = new ArrayList<>();
        List<Thread> pool = new ArrayList<>();
        for (int k = 0; k < keys; k++) {
            final String key = "k" + k;
            final boolean makeBad = k % 5 == 0; // 每 5 个一个非法场景
            for (int d = 0; d < dupPerKey; d++) {
                Thread t = new Thread(() -> {
                    ScenarioDefinition sc = makeBad
                            ? new ScenarioDefinition("SC-BAD", "v1", java.util.Map.of("NOPE", -0.1), null, 0, 1)
                            : TestFixtures.scenario("v1");
                    synchronized (all) {
                        all.add(service.submit(new TrialRequest(key, TestFixtures.portfolio(),
                                TestFixtures.snapshots(8), sc, "v1", 5_000)));
                    }
                    submitted.incrementAndGet();
                });
                pool.add(t);
                t.start();
            }
        }
        for (Thread t : pool) {
            t.join();
        }
        CompletableFuture.allOf(all.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);

        // 终态计数在结果 Future 的完成回调中累加，与 allOf 的完成存在调度先后；
        // 轮询等待计数收敛（终态语义本身不变：每个 key 恰好计入一次）。
        TrialMetrics m;
        long deadline = System.currentTimeMillis() + 5_000;
        do {
            m = service.metrics();
            if (m.succeeded() + m.rejected() + m.failed() + m.timedOut() == keys) {
                break;
            }
            Thread.sleep(20);
        } while (System.currentTimeMillis() < deadline);
        log.info("[mixed-load] total submissions={} metrics={}", submitted.get(), m.asMap());
        assertEquals(keys, m.submitted());
        assertEquals(keys * (dupPerKey - 1), m.deduplicated());
        assertEquals(keys - keys / 5, m.succeeded());
        assertEquals(keys / 5, m.rejected());
        assertEquals(m.submitted(), m.succeeded() + m.rejected() + m.failed() + m.timedOut(),
                "每个唯一 key 的终态必须恰好计入一次");
        service.shutdown();
    }
}
