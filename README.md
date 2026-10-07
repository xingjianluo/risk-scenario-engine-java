# 风险场景分析与压力测试服务（本地运行）

基于本地行情快照与组合持仓的估值 / 风险试算服务。不依赖外部行情源、真实凭据或付费服务，同一输入重复计算结果确定。

## 运行与验证

```bash
mvn test                 # 全部单元测试（31 个，含并发、任务化与边界场景）
mvn package              # 打包
java -jar target/risk-scenario-engine-java-1.0-SNAPSHOT.jar   # 启动，默认 8080 端口
```

接口：

- `POST /api/trials`：提交试算（`TrialRequest` JSON），同步等待并返回 `TrialResult`（上一版用法，保持兼容）。
- `GET /api/trials/metrics`：试算指标（submitted / deduplicated / succeeded / rejected / failed / timedOut）。
- `POST /api/trial-tasks`：任务化提交，**立即返回** `TaskSnapshot`（含 `taskId` 与当前状态），不等待算完。
- `GET /api/trial-tasks/{taskId}`：凭编号查询状态；未知编号返回 404。
- `POST /api/trial-tasks/{taskId}/cancel`：取消未完成任务；已终态则原样返回（幂等）；未知编号返回 404。
- `GET /api/trial-tasks/metrics`：任务层指标（submitted / deduplicated / queued / running / succeeded / rejected / capacityRejected / failed / timedOut / cancelled）。

请求示例见下文「本地验证方法」。

## 估值与风险口径

- **估值**：`value = Σ quantity × price(assetId)`，取最新一期（日期最大）快照为估值基准。
- **场景冲击**：`shockedPrice = price × (1 + shock)`，`scenarioLoss = baseValue − shockedValue`。
- **相关性调整**（场景携带 `correlation` 时）：设持仓 i 的独立损失 `l_i = w_i × (−shock_i)`（`w_i` 为持仓市值），
  组合损失 `L = sign(Σl_i) × sqrt(|Σ_ij l_i l_j c_ij|)`。完全正相关时退化为独立损失之和。调整后报告会附加 `correlation` 告警说明口径变化。
- **历史模拟**：相邻快照组合收益率 `r_t = V_t/V_{t−1} − 1`，损失样本 `loss_t = −r_t × baseValue`（正为损失）。
- **尾部指标**：损失升序排列，VaR(q) 取最近秩分位数 `ceil(q·n)`，ES(q) 为 VaR 秩及更差样本的均值。输出 VaR/ES 95% 与 99%。
- **版本口径**：`requestedScenarioVersion` 与场景实际版本不一致 → 拒绝（`SCENARIO_VERSION_MISMATCH`）；
  未锁定版本（传 null）→ 正常计算但 `comparable=false` 并附告警。同一版本 + 同一输入 ⇒ 结论逐字段一致，可按版本复现。

## 数据校验与拒绝规则

非法或不完整数据一律拒绝（`DataValidationException` / `TrialResult.status=REJECTED`），不静默跳过。拒绝原因可区分：

| RejectionReason | 触发条件 |
|---|---|
| `EMPTY_PORTFOLIO` | 组合无持仓 |
| `MISSING_FIELD` | 缺 portfolioId / instrumentId / assetId / asOfDate / prices / scenarioId / version / idempotencyKey 等必填字段，或数量非法 |
| `NON_POSITIVE_PRICE` | 价格 ≤ 0 或非有限值 |
| `UNKNOWN_ASSET` | 持仓资产在快照中无价格，或场景冲击了未持有资产 |
| `DUPLICATE_SNAPSHOT_DATE` | 多期快照日期重复 |
| `INVALID_CORRELATION_MATRIX` | 维度不符、非对称、对角线非 1、元素越界 [-1,1]、非半正定（Cholesky 检验）、未覆盖全部持仓资产 |
| `INSUFFICIENT_HISTORY` | 收益点数量 < `minHistoryPoints`（或快照不足 2 期且要求历史模拟） |
| `INSUFFICIENT_TAIL_SAMPLES` | 95%/99% 尾部样本数（`ceil((1−q)·n)`）< `minTailSamples` |
| `SCENARIO_VERSION_MISMATCH` | 请求锁定版本与场景实际版本不一致 |
| `CAPACITY_EXCEEDED` | 任务层容量打满：执行槽与排队槽均占满（仅任务化接口产生，见下节） |

**时序乱序**：快照乱序不拒绝，确定性地按日期升序归一化，并在 `warnings` 中留下可解释说明；乱序与顺序输入的结论完全一致。

## 并发与重试语义

- **幂等**：`idempotencyKey` 相同的并发/重复提交共享同一个 `CompletableFuture`，只执行一次；重复提交计入 `deduplicated`，不影响业务计数。
- **隔离**：每个试算在独立工作线程执行，单个试算的拒绝、失败或超时通过其自身 Future 传递，不阻塞、不污染其他试算。
- **超时**：`timeoutMs` 到期未完成则以 `TIMED_OUT` 收尾；迟到的计算结果不得覆盖终态（`complete` 幂等）。
- **指标一致**：每个唯一 key 的终态（succeeded/rejected/failed/timedOut）恰好计入一次，满足
  `submitted = succeeded + rejected + failed + timedOut`；重试同一 key 不重复计入。

## 任务化试算（新增）

在同步试算之上叠加的一层任务化用法，适合提交量大、不想阻塞等待的场景。

### 查询与取消怎么用

1. `POST /api/trial-tasks`（请求体与 `POST /api/trials` 相同的 `TrialRequest`）**立即返回** `TaskSnapshot`，
   其中 `taskId`（= `idempotencyKey`）是后续查询/取消的编号，`status` 为 `QUEUED` 或 `RUNNING`（或容量打满时的 `REJECTED`）。
2. `GET /api/trial-tasks/{taskId}` 随时查询：`QUEUED`（排队中）、`RUNNING`（计算中）、
   `SUCCEEDED` / `REJECTED` / `FAILED` / `TIMED_OUT` / `CANCELLED`（终态，`result` 字段携带完整结果）。
   同一编号反复查询稳定一致；查询是只读操作，不会触发或重复触发计算。
3. `POST /api/trial-tasks/{taskId}/cancel` 取消未完成的任务：
   - 排队中的任务直接出队；执行中的任务释放执行槽并中断工作线程；
   - `CANCELLED` 是独立终态，与超时、失败、拒绝明确区分（不携带 `rejectionReason`）；
   - 取消后迟到的计算结果不得覆盖 `CANCELLED`，也不会重复计数；
   - 取消后再查询返回稳定的 `CANCELLED` 快照（含取消时间）；再用同一编号提交会被去重，
     返回该 `CANCELLED` 快照（计入 `deduplicated`），不会重新计算；
   - 已终态的任务取消是幂等空操作，返回当前状态。

### 容量与排队规则

- 同时执行的任务数上限为 `maxConcurrent`（默认为 CPU 核数，至少 2），超出的自动排队；
- 排队上限为 `maxQueue`（默认 100），队列也排满时新提交直接拒绝：
  `status=REJECTED`、`rejectionReason=CAPACITY_EXCEEDED`，与输入校验类拒绝原因可区分；
- 任务进入终态（含取消、超时）即释放执行槽，队首任务自动补位执行；
- 同一 `idempotencyKey` 重复提交不重复占坑、不重复计入容量，返回同一任务的快照；
- 指标自洽：`submitted = queued + running + succeeded + rejected + failed + timedOut + cancelled`，
  其中 `capacityRejected` 是 `rejected` 的子集；每个唯一编号的终态恰好计入一次。

### 与上一版的兼容范围

- 上一版的 `POST /api/trials`（同步等待结果）与 `GET /api/trials/metrics` 行为完全不变；
- 任务化接口是新增端点，复用同一份 `TrialRequest` / `TrialResult` 模型与全部校验、拒绝规则；
- `TrialStatus` 新增 `QUEUED`、`CANCELLED` 两个枚举值（仅任务化接口产生），
  旧接口不会产生这两个状态；按状态枚举做穷尽分支的调用方需留意。

## 本地验证方法

```bash
# 1. 单元测试（日志含输入摘要、场景版本、判定依据与拒绝原因）
mvn test

# 2. 启动服务后提交试算
curl -X POST http://localhost:8080/api/trials -H 'Content-Type: application/json' -d '{
  "idempotencyKey": "demo-1",
  "portfolio": {"portfolioId": "PF-1", "positions": [
    {"instrumentId": "EQ-A", "assetId": "A", "quantity": 100},
    {"instrumentId": "EQ-B", "assetId": "B", "quantity": 50}]},
  "snapshots": [
    {"asOfDate": "2026-01-01", "prices": {"A": 10.0, "B": 20.0}},
    {"asOfDate": "2026-01-02", "prices": {"A": 10.1, "B": 19.9}},
    {"asOfDate": "2026-01-03", "prices": {"A": 10.201, "B": 19.8005}}
  ],
  "scenario": {"scenarioId": "SC-1", "version": "v1",
               "shocks": {"A": -0.10, "B": 0.05},
               "correlation": null, "minHistoryPoints": 0, "minTailSamples": 1},
  "requestedScenarioVersion": "v1",
  "timeoutMs": 5000
}'

# 3. 查看指标
curl http://localhost:8080/api/trials/metrics

# 4. 任务化提交：立即返回 taskId 与状态（把上面 JSON 中 idempotencyKey 改为 task-1）
curl -X POST http://localhost:8080/api/trial-tasks -H 'Content-Type: application/json' -d '{ ...同上的 TrialRequest... }'

# 5. 凭编号查询 / 取消 / 查看任务层指标
curl http://localhost:8080/api/trial-tasks/task-1
curl -X POST http://localhost:8080/api/trial-tasks/task-1/cancel
curl http://localhost:8080/api/trial-tasks/metrics
```

重复提交同一 `idempotencyKey` 返回相同结果且 `deduplicated` 递增；把某个快照日期复制一份再提交（换 key）可观察到 `REJECTED / DUPLICATE_SNAPSHOT_DATE`。

## 测试覆盖

- `InputValidationTest`：字段缺失、重复快照、乱序归一化确定性、重复计算确定性、非正价格、空组合、未知资产冲击。
- `CorrelationMatrixTest`：非对称 / 非单位对角 / 越界 / 非半正定矩阵拒绝；合法矩阵的相关性调整口径。
- `TailSampleTest`：历史样本不足、尾部样本不足拒绝；版本不匹配拒绝、未锁定版本不可比标注；同版本结论可复现。
- `TrialConcurrencyTest`：并发重复提交只执行一次、重试不重复计数、失败/超时隔离、混合负载下指标一致性。
- `TrialTaskServiceTest`：提交即得编号且查询稳定只读、取消排队/执行中任务（迟到结果不覆盖、取消后提交去重不重复计算）、
  容量打满拒绝原因可区分、重复提交不多占容量、取消/超时/失败/拒绝四种结局可区分、终态释放槽位补位且计数恰好一次。
