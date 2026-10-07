package com.github.xingjianluo.riskscenarioenginejava.tests;

import com.github.xingjianluo.riskscenarioenginejava.domain.*;
import com.github.xingjianluo.riskscenarioenginejava.engine.RiskMetricsCalculator;
import com.github.xingjianluo.riskscenarioenginejava.engine.ScenarioAnalysisService;
import com.github.xingjianluo.riskscenarioenginejava.engine.ValuationEngine;
import com.github.xingjianluo.riskscenarioenginejava.trial.TaskMetrics;
import com.github.xingjianluo.riskscenarioenginejava.trial.TrialRequest;
import com.github.xingjianluo.riskscenarioenginejava.trial.TrialService;
import com.github.xingjianluo.riskscenarioenginejava.trial.TrialTaskService;
import com.github.xingjianluo.riskscenarioenginejava.trial.TrialTaskView;
import com.github.xingjianluo.riskscenarioenginejava.validation.InputValidator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 任务化试算层：提交即返编号、查询、取消、容量与排队、重复提交去重、指标自洽。
 */
class TrialTaskServiceTest {

    private static final Logger log = LoggerFactory.getLogger(TrialTaskServiceTest.class);

    private final ScenarioAnalysisService analysisService =
            new ScenarioAnalysisService(new InputValidator(), new ValuationEngine(), new RiskMetricsCalculator());

    /** 可闸门控制的分析服务：进入时放行 entered，释放前阻塞在 release（带兜底超时）。 */
    private static final class GatedAnalysis extends ScenarioAnalysisService {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        GatedAnalysis() {
            super(new InputValidator(), new ValuationEngine(), new RiskMetricsCalculator());
        }

        @Override
        public RiskReport analyze(Portfolio portfolio, List<MarketSnapshot> snapshots,
                                  ScenarioDefinition scenario, String requestedVersion) {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return super.analyze(portfolio, snapshots, scenario, requestedVersion);
        }
    }

    private TrialRequest request(String key) {
        return new TrialRequest(key, TestFixtures.portfolio(), TestFixtures.snapshots(10),
                TestFixtures.scenario("v1"), "v1", 30_000);
    }

    private static void assertInvariant(TaskMetrics m) {
        assertEquals(m.accepted(), m.queuedNow() + m.runningNow() + m.completed() + m.cancelled(),
                "accepted 必须等于 queued+running+completed+cancelled: " + m.asMap());
    }

    private static TrialTaskView awaitStatus(TrialTaskService svc, String ticket,
                                             TrialStatus expected, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        TrialTaskView v;
        do {
            v = svc.query(ticket);
            if (v.status() == expected) {
                return v;
            }
            Thread.sleep(20);
        } while (System.currentTimeMillis() < deadline);
        return v;
    }

    @Test
    void submitReturnsTicketImmediatelyAndQueryIsStable() throws Exception {
        TrialService trialService = new TrialService(analysisService, 2);
        TrialTaskService svc = new TrialTaskService(trialService, 2, 10);
        TestFixtures.logSummary("task-submit", TestFixtures.portfolio(), TestFixtures.snapshots(10),
                TestFixtures.scenario("v1"));

        TrialTaskView v0 = svc.submit(request("t-1"));
        log.info("[task-submit] ticket={} immediateStatus={}", v0.ticketId(), v0.status());
        assertEquals("t-1", v0.ticketId());
        assertTrue(v0.status() == TrialStatus.QUEUED || v0.status() == TrialStatus.RUNNING
                || v0.status() == TrialStatus.SUCCEEDED, "提交后应立即返回进行中或已完成状态");

        TrialTaskView done = awaitStatus(svc, "t-1", TrialStatus.SUCCEEDED, 10_000);
        log.info("[task-query] ticket={} finalStatus={} detail={}", done.ticketId(), done.status(), done.detail());
        assertEquals(TrialStatus.SUCCEEDED, done.status());
        assertNotNull(done.result());

        // 同一编号反复查询稳定一致，且不触发重复计算
        for (int i = 0; i < 5; i++) {
            TrialTaskView again = svc.query("t-1");
            assertEquals(TrialStatus.SUCCEEDED, again.status());
            assertSame(done.result(), again.result(), "反复查询必须返回同一结果对象");
        }
        TaskMetrics m = svc.metrics();
        log.info("[task-query] metrics={}", m.asMap());
        assertEquals(1, m.accepted());
        assertEquals(1, m.completed());
        assertEquals(0, svc.metrics().deduplicated(), "查询不得计入去重");
        assertInvariant(m);
        svc.shutdown();
        trialService.shutdown();
    }

    @Test
    void cancelQueuedTaskReleasesSlotAndDoesNotPolluteOthers() throws Exception {
        GatedAnalysis gated = new GatedAnalysis();
        TrialService trialService = new TrialService(gated, 2);
        TrialTaskService svc = new TrialTaskService(trialService, 1, 10);

        svc.submit(request("blocker"));
        assertTrue(gated.entered.await(5, TimeUnit.SECONDS), "blocker 应进入执行");
        TrialTaskView queued = svc.submit(request("q-1"));
        assertEquals(TrialStatus.QUEUED, queued.status());
        log.info("[cancel-queued] before cancel: metrics={}", svc.metrics().asMap());

        TrialTaskView cancelled = svc.cancel("q-1");
        log.info("[cancel-queued] ticket={} status={}", cancelled.ticketId(), cancelled.status());
        assertEquals(TrialStatus.CANCELLED, cancelled.status());

        gated.release.countDown();
        TrialTaskView blockerDone = awaitStatus(svc, "blocker", TrialStatus.SUCCEEDED, 10_000);
        assertEquals(TrialStatus.SUCCEEDED, blockerDone.status(), "取消排队任务不得影响其他试算");

        // 取消后查询稳定；再提交同 key 仍是 CANCELLED，不重复计算
        assertEquals(TrialStatus.CANCELLED, svc.query("q-1").status());
        TrialTaskView resubmit = svc.submit(request("q-1"));
        log.info("[cancel-queued] resubmit same key: status={}", resubmit.status());
        assertEquals(TrialStatus.CANCELLED, resubmit.status(), "取消后同 key 再提交必须保持 CANCELLED");

        TaskMetrics m = svc.metrics();
        log.info("[cancel-queued] final metrics={}", m.asMap());
        assertEquals(2, m.accepted());
        assertEquals(1, m.cancelled());
        assertEquals(1, m.completed());
        assertEquals(1, m.deduplicated());
        assertEquals(0, m.queuedNow());
        assertEquals(0, m.runningNow());
        assertInvariant(m);
        svc.shutdown();
        trialService.shutdown();
    }

    @Test
    void cancelRunningTaskDiscardsLateResult() throws Exception {
        GatedAnalysis gated = new GatedAnalysis();
        TrialService trialService = new TrialService(gated, 2);
        TrialTaskService svc = new TrialTaskService(trialService, 2, 10);

        svc.submit(request("run-1"));
        assertTrue(gated.entered.await(5, TimeUnit.SECONDS));
        TrialTaskView running = awaitStatus(svc, "run-1", TrialStatus.RUNNING, 5_000);
        assertEquals(TrialStatus.RUNNING, running.status());

        TrialTaskView cancelled = svc.cancel("run-1");
        log.info("[cancel-running] ticket={} status={}", cancelled.ticketId(), cancelled.status());
        assertEquals(TrialStatus.CANCELLED, cancelled.status());

        gated.release.countDown();
        // 底层计算随后完成，但迟到结果不得覆盖 CANCELLED
        awaitStatus(svc, "run-1", TrialStatus.CANCELLED, 10_000);
        Thread.sleep(300); // 给迟到结果覆盖留出时间窗
        TrialTaskView after = svc.query("run-1");
        log.info("[cancel-running] after late completion: status={} metrics={}",
                after.status(), svc.metrics().asMap());
        assertEquals(TrialStatus.CANCELLED, after.status(), "迟到结果不得覆盖 CANCELLED");

        TaskMetrics m = svc.metrics();
        assertEquals(1, m.accepted());
        assertEquals(1, m.cancelled());
        assertEquals(0, m.completed(), "被取消任务不得计入 completed");
        // 运行中被取消：runningNow 应随工作线程退出而归零
        awaitZeroRunning(svc);
        assertInvariant(svc.metrics());
        svc.shutdown();
        trialService.shutdown();
    }

    private void awaitZeroRunning(TrialTaskService svc) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && svc.metrics().runningNow() != 0) {
            Thread.sleep(20);
        }
        assertEquals(0, svc.metrics().runningNow(), "取消后 runningNow 必须归零");
    }

    @Test
    void capacityFullRejectsWithDistinctReason() throws Exception {
        GatedAnalysis gated = new GatedAnalysis();
        TrialService trialService = new TrialService(gated, 2);
        TrialTaskService svc = new TrialTaskService(trialService, 1, 1);

        svc.submit(request("blocker"));                       // 占满执行位
        assertTrue(gated.entered.await(5, TimeUnit.SECONDS));
        TrialTaskView queued = svc.submit(request("queued")); // 占满唯一队列槽
        assertEquals(TrialStatus.QUEUED, queued.status());

        TrialTaskView over = svc.submit(request("overflow"));
        log.info("[capacity] overflow submit: status={} reason={} detail={}",
                over.status(), over.result() == null ? null : over.result().rejectionReason(), over.detail());
        assertEquals(TrialStatus.REJECTED, over.status());
        assertEquals(RejectionReason.CAPACITY_EXCEEDED, over.result().rejectionReason(),
                "容量打满的拒绝原因必须可与数据类拒绝区分");

        // 容量拒绝可查询、稳定；与数据校验拒绝（UNKNOWN_ASSET）原因不同
        TrialTaskView queried = svc.query("overflow");
        assertEquals(TrialStatus.REJECTED, queried.status());
        assertEquals(RejectionReason.CAPACITY_EXCEEDED, queried.result().rejectionReason());

        gated.release.countDown();
        awaitStatus(svc, "blocker", TrialStatus.SUCCEEDED, 10_000);
        awaitStatus(svc, "queued", TrialStatus.SUCCEEDED, 10_000);

        TaskMetrics m = svc.metrics();
        log.info("[capacity] final metrics={}", m.asMap());
        assertEquals(2, m.accepted(), "容量拒绝不占坑、不计入 accepted");
        assertEquals(1, m.capacityRejected());
        assertEquals(2, m.completed());
        assertEquals(0, m.queuedNow());
        assertInvariant(m);
        svc.shutdown();
        trialService.shutdown();
    }

    @Test
    void duplicateSubmissionDoesNotDoubleOccupyCapacity() throws Exception {
        GatedAnalysis gated = new GatedAnalysis();
        TrialService trialService = new TrialService(gated, 2);
        TrialTaskService svc = new TrialTaskService(trialService, 1, 1);

        svc.submit(request("blocker"));
        assertTrue(gated.entered.await(5, TimeUnit.SECONDS));

        // 同一 key 并发重复提交：只占一个队列槽
        int threads = 8;
        CountDownLatch go = new CountDownLatch(1);
        List<TrialTaskView> views = new ArrayList<>();
        List<Thread> pool = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                synchronized (views) {
                    views.add(svc.submit(request("dup")));
                }
            });
            pool.add(t);
            t.start();
        }
        go.countDown();
        for (Thread t : pool) {
            t.join();
        }
        for (TrialTaskView v : views) {
            assertEquals("dup", v.ticketId());
            assertEquals(TrialStatus.QUEUED, v.status(), "重复提交不得改变排队状态");
        }
        TaskMetrics m1 = svc.metrics();
        log.info("[dup-capacity] after dup submissions: metrics={}", m1.asMap());
        assertEquals(2, m1.accepted(), "重复提交不得重复占坑");
        assertEquals(threads - 1, m1.deduplicated());

        // 队列仍只剩 0 个空位：另一个不同 key 应被容量拒绝，证明 dup 没多占槽
        TrialTaskView other = svc.submit(request("other"));
        log.info("[capacity] other key while queue full: status={} reason={}",
                other.status(), other.result() == null ? null : other.result().rejectionReason());
        assertEquals(TrialStatus.REJECTED, other.status());
        assertEquals(RejectionReason.CAPACITY_EXCEEDED, other.result().rejectionReason());

        gated.release.countDown();
        awaitStatus(svc, "blocker", TrialStatus.SUCCEEDED, 10_000);
        awaitStatus(svc, "dup", TrialStatus.SUCCEEDED, 10_000);
        TaskMetrics m = svc.metrics();
        log.info("[dup-capacity] final metrics={}", m.asMap());
        assertEquals(2, m.completed());
        assertEquals(1, m.capacityRejected());
        assertInvariant(m);
        svc.shutdown();
        trialService.shutdown();
    }

    @Test
    void cancelTerminalAndUnknownTicketAreDeterministic() throws Exception {
        TrialService trialService = new TrialService(analysisService, 2);
        TrialTaskService svc = new TrialTaskService(trialService, 2, 10);

        svc.submit(request("done-1"));
        awaitStatus(svc, "done-1", TrialStatus.SUCCEEDED, 10_000);

        // 已终态的取消是幂等空操作，返回当前终态
        TrialTaskView cancelDone = svc.cancel("done-1");
        log.info("[cancel-terminal] ticket={} status={}", cancelDone.ticketId(), cancelDone.status());
        assertEquals(TrialStatus.SUCCEEDED, cancelDone.status());
        assertEquals(0, svc.metrics().cancelled(), "终态取消不得计入 cancelled");

        // 未知编号：查询与取消都返回 status=null 的可识别快照
        TrialTaskView unknownQuery = svc.query("nope");
        TrialTaskView unknownCancel = svc.cancel("nope");
        log.info("[unknown-ticket] query={} cancel={}", unknownQuery.detail(), unknownCancel.detail());
        assertNull(unknownQuery.status());
        assertNull(unknownCancel.status());

        // 缺 key 的提交：立即拒绝且不占容量
        TrialTaskView noKey = svc.submit(new TrialRequest(null, TestFixtures.portfolio(),
                TestFixtures.snapshots(5), TestFixtures.scenario("v1"), "v1", 5_000));
        log.info("[missing-key] status={} reason={}", noKey.status(),
                noKey.result() == null ? null : noKey.result().rejectionReason());
        assertEquals(TrialStatus.REJECTED, noKey.status());
        assertEquals(RejectionReason.MISSING_FIELD, noKey.result().rejectionReason());

        TaskMetrics m = svc.metrics();
        log.info("[cancel-terminal] final metrics={}", m.asMap());
        assertEquals(1, m.accepted());
        assertEquals(1, m.completed());
        assertInvariant(m);
        svc.shutdown();
        trialService.shutdown();
    }

    @Test
    void cancelledDistinctFromTimeoutFailureAndRejection() throws Exception {
        GatedAnalysis gated = new GatedAnalysis();
        TrialService trialService = new TrialService(gated, 4);
        TrialTaskService svc = new TrialTaskService(trialService, 4, 10);

        // 取消一个排队任务
        svc.submit(request("blocker"));
        assertTrue(gated.entered.await(5, TimeUnit.SECONDS));
        svc.submit(request("to-cancel"));
        svc.cancel("to-cancel");

        // 数据拒绝：场景冲击未持有资产
        ScenarioDefinition bad = new ScenarioDefinition("SC-BAD", "v1",
                java.util.Map.of("NOPE", -0.1), null, 0, 1);
        svc.submit(new TrialRequest("bad-data", TestFixtures.portfolio(),
                TestFixtures.snapshots(5), bad, "v1", 30_000));

        gated.release.countDown();
        awaitStatus(svc, "blocker", TrialStatus.SUCCEEDED, 10_000);
        awaitStatus(svc, "bad-data", TrialStatus.REJECTED, 10_000);

        TrialTaskView c = svc.query("to-cancel");
        TrialTaskView r = svc.query("bad-data");
        log.info("[distinct] cancelled={} rejected={} reason={}", c.status(), r.status(),
                r.result().rejectionReason());
        assertEquals(TrialStatus.CANCELLED, c.status());
        assertEquals(TrialStatus.REJECTED, r.status());
        assertEquals(RejectionReason.UNKNOWN_ASSET, r.result().rejectionReason());
        assertNotEquals(c.status(), r.status(), "取消必须与拒绝明确区分");

        TaskMetrics m = svc.metrics();
        log.info("[distinct] metrics={}", m.asMap());
        assertEquals(1, m.cancelled());
        assertEquals(2, m.completed());
        assertInvariant(m);
        svc.shutdown();
        trialService.shutdown();
    }
}
