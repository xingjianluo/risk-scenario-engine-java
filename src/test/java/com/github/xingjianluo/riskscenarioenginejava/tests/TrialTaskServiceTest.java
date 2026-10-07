package com.github.xingjianluo.riskscenarioenginejava.tests;

import com.github.xingjianluo.riskscenarioenginejava.domain.*;
import com.github.xingjianluo.riskscenarioenginejava.engine.RiskMetricsCalculator;
import com.github.xingjianluo.riskscenarioenginejava.engine.ScenarioAnalysisService;
import com.github.xingjianluo.riskscenarioenginejava.engine.ValuationEngine;
import com.github.xingjianluo.riskscenarioenginejava.task.TaskMetrics;
import com.github.xingjianluo.riskscenarioenginejava.task.TaskSnapshot;
import com.github.xingjianluo.riskscenarioenginejava.task.TrialTaskService;
import com.github.xingjianluo.riskscenarioenginejava.trial.TrialRequest;
import com.github.xingjianluo.riskscenarioenginejava.validation.InputValidator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 任务化试算层：提交即得编号、状态查询、取消、容量与排队、重复提交去重。
 * 日志包含输入摘要、状态迁移、判定依据与拒绝原因。
 */
class TrialTaskServiceTest {

    private static final Logger log = LoggerFactory.getLogger(TrialTaskServiceTest.class);

    private final ScenarioAnalysisService analysisService =
            new ScenarioAnalysisService(new InputValidator(), new ValuationEngine(), new RiskMetricsCalculator());

    private TrialRequest request(String key) {
        return new TrialRequest(key, TestFixtures.portfolio(), TestFixtures.snapshots(10),
                TestFixtures.scenario("v1"), "v1", 5_000);
    }

    /** 慢速分析：休眠 sleepMs（响应中断后仍继续算完，模拟不可中断计算），并计数调用次数。 */
    private ScenarioAnalysisService slowAnalysis(long sleepMs, AtomicInteger invocations) {
        return new ScenarioAnalysisService(new InputValidator(), new ValuationEngine(), new RiskMetricsCalculator()) {
            @Override
            public RiskReport analyze(Portfolio portfolio, List<MarketSnapshot> snapshots,
                                      ScenarioDefinition scenario, String requestedVersion) {
                invocations.incrementAndGet();
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.analyze(portfolio, snapshots, scenario, requestedVersion);
            }
        };
    }

    private TaskSnapshot awaitStatus(TrialTaskService service, String taskId,
                                     TrialStatus expected, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        TaskSnapshot snap;
        do {
            snap = service.query(taskId);
            if (snap != null && snap.status() == expected) {
                return snap;
            }
            Thread.sleep(20);
        } while (System.currentTimeMillis() < deadline);
        return service.query(taskId);
    }

    private void assertMetricsConsistent(String caseName, TaskMetrics m) {
        log.info("[{}] metrics={}", caseName, m.asMap());
        assertEquals(m.submitted(),
                m.queued() + m.running() + m.succeeded() + m.rejected()
                        + m.failed() + m.timedOut() + m.cancelled(),
                "submitted 必须等于在途与各终态之和");
        assertTrue(m.capacityRejected() <= m.rejected(), "容量拒绝是拒绝的子集");
    }

    @Test
    void submitReturnsIdImmediatelyAndQueryIsStableAndReadOnly() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        TrialTaskService service = new TrialTaskService(slowAnalysis(300, invocations), 2, 10);
        TestFixtures.logSummary("task-submit", TestFixtures.portfolio(), TestFixtures.snapshots(10),
                TestFixtures.scenario("v1"));

        long t0 = System.currentTimeMillis();
        TaskSnapshot submitted = service.submit(request("t-immediate"));
        long elapsed = System.currentTimeMillis() - t0;
        log.info("[task-submit] taskId={} status={} returnedIn={}ms",
                submitted.taskId(), submitted.status(), elapsed);
        assertEquals("t-immediate", submitted.taskId(), "提交后立即拿到可查询编号");
        assertEquals(TrialStatus.RUNNING, submitted.status());
        assertTrue(elapsed < 300, "提交不得阻塞等待计算完成");

        // 计算中反复查询：状态稳定一致，且查询不触发重复计算
        TaskSnapshot q1 = service.query("t-immediate");
        TaskSnapshot q2 = service.query("t-immediate");
        assertEquals(q1.status(), q2.status());
        assertEquals(q1.taskId(), q2.taskId());

        TaskSnapshot done = awaitStatus(service, "t-immediate", TrialStatus.SUCCEEDED, 5_000);
        log.info("[task-submit] final status={} startedAt={} finishedAt={}",
                done.status(), done.startedAtMs(), done.finishedAtMs());
        assertEquals(TrialStatus.SUCCEEDED, done.status());
        assertNotNull(done.result());
        assertNotNull(done.result().report());

        // 终态后多次查询稳定一致，且全程只计算一次
        assertEquals(TrialStatus.SUCCEEDED, service.query("t-immediate").status());
        assertEquals(TrialStatus.SUCCEEDED, service.query("t-immediate").status());
        assertEquals(1, invocations.get(), "查询不得重复触发计算");
        assertNull(service.query("no-such-task"), "未知编号查询返回 null");
        assertMetricsConsistent("task-submit", service.metrics());
        service.shutdown();
    }

    @Test
    void cancelQueuedTaskFreesSlotAndStaysTraceable() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        TrialTaskService service = new TrialTaskService(slowAnalysis(400, invocations), 1, 1);
        TestFixtures.logSummary("cancel-queued", TestFixtures.portfolio(), TestFixtures.snapshots(10),
                TestFixtures.scenario("v1"));

        TaskSnapshot t1 = service.submit(request("c-run"));
        TaskSnapshot t2 = service.submit(request("c-queue"));
        TaskSnapshot t3 = service.submit(request("c-overflow"));
        log.info("[cancel-queued] t1={} t2={} t3={} reason={}",
                t1.status(), t2.status(), t3.status(),
                t3.result() != null ? t3.result().rejectionReason() : null);
        assertEquals(TrialStatus.RUNNING, t1.status());
        assertEquals(TrialStatus.QUEUED, t2.status());
        assertEquals(TrialStatus.REJECTED, t3.status(), "排队已满必须拒绝");
        assertEquals(RejectionReason.CAPACITY_EXCEEDED, t3.result().rejectionReason());

        // 取消排队任务：释放队列槽，状态为独立终态 CANCELLED
        TaskSnapshot cancelled = service.cancel("c-queue");
        log.info("[cancel-queued] cancel c-queue -> status={} detail={}",
                cancelled.status(), cancelled.detail());
        assertEquals(TrialStatus.CANCELLED, cancelled.status());
        assertNotNull(cancelled.finishedAtMs());

        // 队列槽已释放：新任务可以排入
        TaskSnapshot t4 = service.submit(request("c-after-cancel"));
        log.info("[cancel-queued] after cancel, t4 status={}", t4.status());
        assertEquals(TrialStatus.QUEUED, t4.status());

        // 取消后同编号查询稳定为 CANCELLED；重复提交去重且不重复计算
        assertEquals(TrialStatus.CANCELLED, service.query("c-queue").status());
        TaskSnapshot resub = service.submit(request("c-queue"));
        log.info("[cancel-queued] resubmit cancelled key -> status={}", resub.status());
        assertEquals(TrialStatus.CANCELLED, resub.status(), "取消后重复提交不得重新计算");
        assertEquals(1, service.metrics().deduplicated());

        // 取消的任务不得再被派发执行；等 t1、t4 算完后核对
        awaitStatus(service, "c-run", TrialStatus.SUCCEEDED, 5_000);
        awaitStatus(service, "c-after-cancel", TrialStatus.SUCCEEDED, 5_000);
        assertEquals(TrialStatus.CANCELLED, service.query("c-queue").status());
        assertEquals(2, invocations.get(), "被取消的排队任务不得占用计算资源");
        TaskMetrics m = service.metrics();
        assertEquals(1, m.cancelled());
        assertEquals(2, m.succeeded());
        assertEquals(1, m.capacityRejected());
        assertMetricsConsistent("cancel-queued", m);
        service.shutdown();
    }

    @Test
    void cancelRunningTaskDiscardsLateResultAndUnblocksQueue() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        TrialTaskService service = new TrialTaskService(slowAnalysis(1_500, invocations), 1, 5);
        TestFixtures.logSummary("cancel-running", TestFixtures.portfolio(), TestFixtures.snapshots(10),
                TestFixtures.scenario("v1"));

        TaskSnapshot t1 = service.submit(request("r-run"));
        TaskSnapshot t2 = service.submit(request("r-queue"));
        assertEquals(TrialStatus.RUNNING, t1.status());
        assertEquals(TrialStatus.QUEUED, t2.status());

        TaskSnapshot cancelled = service.cancel("r-run");
        log.info("[cancel-running] cancel r-run -> status={} detail={}",
                cancelled.status(), cancelled.detail());
        assertEquals(TrialStatus.CANCELLED, cancelled.status());

        // 取消释放执行槽：排队任务被补位派发
        TaskSnapshot t2Running = awaitStatus(service, "r-queue", TrialStatus.RUNNING, 2_000);
        log.info("[cancel-running] r-queue after cancel -> status={}", t2Running.status());
        assertEquals(TrialStatus.RUNNING, t2Running.status());

        // 等被取消任务的迟到计算结束（1.5s 休眠），迟到结果不得覆盖 CANCELLED
        Thread.sleep(2_000);
        TaskSnapshot afterLate = service.query("r-run");
        log.info("[cancel-running] late completion arrived, r-run status={}", afterLate.status());
        assertEquals(TrialStatus.CANCELLED, afterLate.status(), "迟到结果不得覆盖 CANCELLED");

        awaitStatus(service, "r-queue", TrialStatus.SUCCEEDED, 5_000);
        TaskMetrics m = service.metrics();
        assertEquals(1, m.cancelled());
        assertEquals(1, m.succeeded());
        assertEquals(0, m.timedOut(), "取消不得被记为超时");
        assertEquals(0, m.failed(), "取消不得被记为失败");
        assertMetricsConsistent("cancel-running", m);
        service.shutdown();
    }

    @Test
    void capacityFullRejectsWithDistinctReason() {
        AtomicInteger invocations = new AtomicInteger();
        TrialTaskService service = new TrialTaskService(slowAnalysis(500, invocations), 1, 0);
        TestFixtures.logSummary("capacity-full", TestFixtures.portfolio(), TestFixtures.snapshots(10),
                TestFixtures.scenario("v1"));

        TaskSnapshot t1 = service.submit(request("cap-1"));
        TaskSnapshot t2 = service.submit(request("cap-2"));
        log.info("[capacity-full] t1={} t2={} reason={} detail={}",
                t1.status(), t2.status(),
                t2.result() != null ? t2.result().rejectionReason() : null, t2.detail());
        assertEquals(TrialStatus.RUNNING, t1.status());
        assertEquals(TrialStatus.REJECTED, t2.status());
        assertEquals(RejectionReason.CAPACITY_EXCEEDED, t2.result().rejectionReason(),
                "容量拒绝原因必须可与输入类拒绝区分");
        assertNotEquals(RejectionReason.UNKNOWN_ASSET, t2.result().rejectionReason());

        // 容量拒绝的任务保持稳定可查，不占队列与执行位
        assertEquals(TrialStatus.REJECTED, service.query("cap-2").status());
        TaskMetrics m = service.metrics();
        assertEquals(0, m.queued());
        assertEquals(1, m.running());
        assertEquals(1, m.capacityRejected());
        assertEquals(1, m.rejected());
        assertMetricsConsistent("capacity-full", m);
        service.shutdown();
    }

    @Test
    void duplicateSubmissionDoesNotDoubleOccupyCapacity() {
        AtomicInteger invocations = new AtomicInteger();
        TrialTaskService service = new TrialTaskService(slowAnalysis(400, invocations), 1, 1);
        TestFixtures.logSummary("dup-capacity", TestFixtures.portfolio(), TestFixtures.snapshots(10),
                TestFixtures.scenario("v1"));

        TaskSnapshot s1 = service.submit(request("dup-cap"));
        TaskSnapshot s2 = service.submit(request("dup-cap"));
        TaskSnapshot other = service.submit(request("other"));
        log.info("[dup-capacity] s1={} s2={} other={}", s1.status(), s2.status(), other.status());
        assertEquals(TrialStatus.RUNNING, s1.status());
        assertEquals(TrialStatus.RUNNING, s2.status(), "重复提交返回同一任务状态");
        assertEquals(TrialStatus.QUEUED, other.status(), "重复提交不得多占执行槽");

        TaskMetrics m = service.metrics();
        assertEquals(1, m.submitted() - 1, "other 之外的唯一任务只有 1 个");
        assertEquals(1, m.deduplicated());
        assertEquals(1, m.running(), "同一 key 不得重复占坑");
        assertEquals(1, m.queued());
        assertMetricsConsistent("dup-capacity", m);
        service.shutdown();
    }

    @Test
    void cancelTimeoutFailureAndRejectionAreDistinctOutcomes() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        TrialTaskService service = new TrialTaskService(slowAnalysis(1_500, invocations), 2, 5);
        TestFixtures.logSummary("distinct-outcomes", TestFixtures.portfolio(), TestFixtures.snapshots(10),
                TestFixtures.scenario("v1"));

        // 取消：RUNNING 时取消
        service.submit(request("d-cancel"));
        TaskSnapshot cancelledSnap = service.cancel("d-cancel");
        // 超时：timeoutMs=200 < 休眠 1500ms
        service.submit(new TrialRequest("d-timeout", TestFixtures.portfolio(), TestFixtures.snapshots(10),
                TestFixtures.scenario("v1"), "v1", 200));
        // 拒绝：场景冲击未持有资产
        ScenarioDefinition bad = new ScenarioDefinition("SC-BAD", "v1",
                java.util.Map.of("NOPE", -0.1), null, 0, 1);
        service.submit(new TrialRequest("d-reject", TestFixtures.portfolio(),
                TestFixtures.snapshots(5), bad, "v1", 5_000));

        TaskSnapshot timeoutSnap = awaitStatus(service, "d-timeout", TrialStatus.TIMED_OUT, 5_000);
        TaskSnapshot rejectSnap = awaitStatus(service, "d-reject", TrialStatus.REJECTED, 5_000);
        log.info("[distinct-outcomes] cancelled={} timeout={} rejected={} rejectReason={}",
                cancelledSnap.status(), timeoutSnap.status(), rejectSnap.status(),
                rejectSnap.result() != null ? rejectSnap.result().rejectionReason() : null);

        assertEquals(TrialStatus.CANCELLED, cancelledSnap.status());
        assertEquals(TrialStatus.TIMED_OUT, timeoutSnap.status());
        assertEquals(TrialStatus.REJECTED, rejectSnap.status());
        assertEquals(RejectionReason.UNKNOWN_ASSET, rejectSnap.result().rejectionReason());
        assertNull(cancelledSnap.result().rejectionReason(), "取消不得携带拒绝原因");

        // 等迟到的计算全部结束，终态不被覆盖、计数不重复
        Thread.sleep(2_000);
        assertEquals(TrialStatus.CANCELLED, service.query("d-cancel").status());
        assertEquals(TrialStatus.TIMED_OUT, service.query("d-timeout").status());
        TaskMetrics m = service.metrics();
        assertEquals(1, m.cancelled());
        assertEquals(1, m.timedOut());
        assertEquals(1, m.rejected());
        assertEquals(0, m.capacityRejected());
        assertMetricsConsistent("distinct-outcomes", m);
        service.shutdown();
    }

    @Test
    void terminalTaskFreesSlotForQueuedAndCountsOnce() throws Exception {
        TrialTaskService service = new TrialTaskService(analysisService, 1, 5);
        TestFixtures.logSummary("slot-recycle", TestFixtures.portfolio(), TestFixtures.snapshots(10),
                TestFixtures.scenario("v1"));

        // 第一个任务会被输入校验拒绝（终态），排队任务应随即补位执行
        ScenarioDefinition bad = new ScenarioDefinition("SC-BAD", "v1",
                java.util.Map.of("NOPE", -0.1), null, 0, 1);
        service.submit(new TrialRequest("s-bad", TestFixtures.portfolio(),
                TestFixtures.snapshots(5), bad, "v1", 5_000));
        service.submit(request("s-good"));

        TaskSnapshot badSnap = awaitStatus(service, "s-bad", TrialStatus.REJECTED, 5_000);
        TaskSnapshot good = awaitStatus(service, "s-good", TrialStatus.SUCCEEDED, 5_000);
        log.info("[slot-recycle] bad={} reason={} good={}", badSnap.status(),
                badSnap.result().rejectionReason(), good.status());
        assertEquals(TrialStatus.REJECTED, badSnap.status());
        assertEquals(TrialStatus.SUCCEEDED, good.status(), "前置任务终态后排队任务必须补位执行");

        TaskMetrics m = service.metrics();
        assertEquals(1, m.rejected());
        assertEquals(1, m.succeeded());
        assertEquals(0, m.queued());
        assertEquals(0, m.running());
        assertMetricsConsistent("slot-recycle", m);
        service.shutdown();
    }
}
