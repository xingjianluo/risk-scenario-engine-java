package com.github.xingjianluo.riskscenarioenginejava.tests;

import com.github.xingjianluo.riskscenarioenginejava.model.BatchTrialRequest;
import com.github.xingjianluo.riskscenarioenginejava.model.MarketSnapshot;
import com.github.xingjianluo.riskscenarioenginejava.model.Portfolio;
import com.github.xingjianluo.riskscenarioenginejava.model.RejectCode;
import com.github.xingjianluo.riskscenarioenginejava.model.ResultStatus;
import com.github.xingjianluo.riskscenarioenginejava.model.RiskResult;
import com.github.xingjianluo.riskscenarioenginejava.model.ScenarioDefinition;
import com.github.xingjianluo.riskscenarioenginejava.model.TrialRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端集成测试：真实启动 Web 容器（随机端口），
 * 覆盖正常试算、确定性拒绝、批量混合结论、幂等查询与健康检查。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RiskControllerIntegrationTest {

    private static final Logger LOG = Logger.getLogger(RiskControllerIntegrationTest.class.getName());

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private TrialRequest request(String id, boolean valid) {
        Portfolio portfolio = TestData.portfolio("PF-WEB", 100, 50);
        List<MarketSnapshot> snapshots = valid
                ? TestData.snapshots(5, 100, 20, 0.2, 0.05)
                : List.of();
        ScenarioDefinition scenario = TestData.shockScenario("S1", TestData.V_SHOCK, -0.1, 0.02);
        return new TrialRequest(id, portfolio, snapshots, scenario, null, null);
    }

    @Test
    @DisplayName("POST /api/risk/trials 正常试算返回 ACCEPTED 与指标")
    void singleTrialAccepted() {
        ResponseEntity<RiskResult> response = rest.postForEntity(
                url("/api/risk/trials"), request("WEB-1", true), RiskResult.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        RiskResult body = response.getBody();
        assertNotNull(body);
        assertEquals(ResultStatus.ACCEPTED, body.status());
        assertEquals(TestData.V_SHOCK, body.scenarioVersion());
        assertNotNull(body.inputDigest());
        LOG.info("[WEB] 输入摘要: PF-WEB 5期快照; 场景版本=" + body.scenarioVersion()
                + "; 判定依据: 确定性冲击; digest=" + body.inputDigest());
    }

    @Test
    @DisplayName("非法请求返回 HTTP 200 + REJECTED，拒绝原因可区分")
    void rejectedTrialDistinguishable() {
        ResponseEntity<RiskResult> response = rest.postForEntity(
                url("/api/risk/trials"), request("WEB-2", false), RiskResult.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        RiskResult body = response.getBody();
        assertNotNull(body);
        assertEquals(ResultStatus.REJECTED, body.status());
        assertEquals(RejectCode.SNAPSHOT_LIST_EMPTY, body.rejectCode());
        LOG.info("[WEB] 判定依据: 快照为空; 拒绝原因=" + body.rejectCode());
    }

    @Test
    @DisplayName("批量混合：正常与拒绝结论在同一批中互不影响")
    void batchMixedResults() {
        BatchTrialRequest batch = new BatchTrialRequest(List.of(
                request("WEB-3", true),
                request("WEB-4", false),
                request("WEB-5", true)));
        ResponseEntity<RiskResult[]> response = rest.postForEntity(
                url("/api/risk/trials/batch"), batch, RiskResult[].class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        RiskResult[] bodies = response.getBody();
        assertNotNull(bodies);
        assertEquals(3, bodies.length);
        assertEquals(ResultStatus.ACCEPTED, bodies[0].status());
        assertEquals(ResultStatus.REJECTED, bodies[1].status());
        assertEquals(ResultStatus.ACCEPTED, bodies[2].status());
        assertEquals(1, bodies[0].sampleCount());
        assertEquals(0, bodies[1].sampleCount());
        LOG.info("[WEB] 批量3个试算: ACCEPTED/REJECTED/ACCEPTED 互不污染");
    }

    @Test
    @DisplayName("重复提交后 GET 查询返回同一幂等结论")
    void idempotentQuery() {
        rest.postForEntity(url("/api/risk/trials"), request("WEB-6", true), RiskResult.class);
        rest.postForEntity(url("/api/risk/trials"), request("WEB-6", true), RiskResult.class);
        ResponseEntity<RiskResult> response = rest.getForEntity(
                url("/api/risk/trials/WEB-6"), RiskResult.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(ResultStatus.ACCEPTED, response.getBody().status());

        ResponseEntity<RiskResult> missing = rest.getForEntity(
                url("/api/risk/trials/NOT-EXIST"), RiskResult.class);
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        LOG.info("[WEB] 判定依据: requestId幂等缓存; 查询命中，未知ID返回404");
    }

    @Test
    @DisplayName("健康检查可用")
    void health() {
        ResponseEntity<String> response = rest.getForEntity(url("/api/risk/health"), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("UP"));
    }

    @Test
    @DisplayName("乱序快照经HTTP提交得到与顺序提交相同的输入摘要")
    void shuffledHttpInputDeterministic() {
        TrialRequest ordered = request("WEB-7", true);
        List<MarketSnapshot> reversed = new ArrayList<>(ordered.snapshots());
        java.util.Collections.reverse(reversed);
        TrialRequest shuffled = new TrialRequest("WEB-8", ordered.portfolio(), reversed,
                ordered.scenario(), null, null);

        RiskResult r1 = rest.postForEntity(url("/api/risk/trials"), ordered, RiskResult.class).getBody();
        RiskResult r2 = rest.postForEntity(url("/api/risk/trials"), shuffled, RiskResult.class).getBody();
        assertNotNull(r1);
        assertNotNull(r2);
        assertEquals(r1.inputDigest(), r2.inputDigest());
        LOG.info("[WEB] 乱序vs顺序摘要一致: " + r1.inputDigest());
    }

    @Test
    @DisplayName("畸形JSON：HTTP 400 + FAILED，与业务 REJECTED 可区分")
    void malformedJsonReturnsFailed() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        org.springframework.http.HttpEntity<String> entity =
                new org.springframework.http.HttpEntity<>("{not-json", headers);
        org.springframework.http.ResponseEntity<RiskResult> response = rest.postForEntity(
                url("/api/risk/trials"), entity, RiskResult.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(com.github.xingjianluo.riskscenarioenginejava.model.ResultStatus.FAILED,
                response.getBody().status());
        LOG.info("[WEB] 判定依据: JSON无法解析; 失败原因=" + response.getBody().rejectReason());
    }
}
