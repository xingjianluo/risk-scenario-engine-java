# 风险场景分析与压力测试服务（本地可运行）

基于本地行情快照与组合持仓进行**估值、场景分析与尾部风险计量**的 Spring Boot 服务。
不依赖任何外部行情源、真实凭据或付费服务：所有输入随请求提交，所有计算在本地确定性完成。

- Java 21+ / Spring Boot 3.4 / 无数据库 / 无外部网络依赖
- 构建：`mvn package`；测试：`mvn test`（共 56 个用例，离线可跑）

---

## 1. 估值与风险口径

### 1.1 估值（线性/一阶口径）

- 组合现值：`V = Σ quantity_i × price_i`（多空净值之和，允许做空，可为负）。
- 给定各标的收益率 `r_i` 的组合损益：`PnL = Σ quantity_i × price_i × r_i`。
- 价格口径：使用**最新一期快照**（按日期升序后的最大日期），必须为正的有限数。
- 不引入凸性/Greeks 等二阶项；口径简单、确定、可解释。

### 1.2 三类场景

| 场景类型 | 样本来源 | 样本计数 | 尾部指标 |
|---|---|---|---|
| `SCENARIO_SHOCK` 场景冲击 | 直接施加场景给定的收益率冲击 | 1 | 不计算（VaR/ES 记 0，notes 标注） |
| `CORRELATION_ADJUST` 相关性调整 | 给定相关结构 + 快照历史波动率的高斯蒙特卡洛 | = `sampleCount`（≥20） | VaR/ES |
| `HISTORICAL_SIMULATION` 历史模拟 | 场景内嵌的历史收益率序列逐期重估 | = 历史期数（≥20） | VaR/ES |

- **损失约定**：损失样本正数表示亏损、负数表示盈利（`loss = -PnL`）。
- **VaR（历史模拟法）**：损失升序排列后取第 `⌈q·N⌉` 个次序统计量（1-based），**无插值、无分布假设**。
- **ES（条件尾部期望）**：VaR 切点及以上损失（含切点）的算术平均，因此恒有 `ES ≥ VaR`。
- 置信水平：`95%` 与 `99%` 同时给出。
- **相关性调整**：
  - 相关矩阵必须通过 Cholesky 半正定判定（见第 2 节）；
  - 单标的波动率 = 多期快照价格的对数收益率样本标准差（至少 2 个收益对，即 ≥3 期快照），
    零方差时回退为均值绝对收益率；
  - 高斯随机数仅由场景 `seed` 决定，同种子同输入的样本序列**逐点一致**，可复现；
  - 未进入相关矩阵的持有标的按零收益处理。
- **历史模拟**：样本及顺序完全取自场景定义，不重采样、不复制，样本计数=期数。

### 1.3 场景版本与可比性

- 场景必须带非空 `version`，结果原样回填 `scenarioVersion`。
- 两期结论的可比性由 `RiskResult.comparabilityWith(...)` 判定：
  仅当两者均 `ACCEPTED`、同 `scenarioId`、同 `scenarioVersion`、同样本计数时才可比；
  **场景版本变化明确标注“不可比”并给出原因**，历史结论可按各自版本用相同输入复现。

---

## 2. 数据校验与拒绝规则

原则：**非法或不完整数据一律确定性拒绝（`REJECTED` + 原因码），绝不静默跳过。**
唯一允许的“改写”是把乱序快照按日期升序归一化，并在结果 `notes` 中留痕。

### 2.1 组合与快照

| 情形 | 拒绝码 |
|---|---|
| 组合缺失 | `PORTFOLIO_MISSING` |
| 组合 ID 缺失/空白 | `PORTFOLIO_ID_MISSING` |
| 持仓为空 | `PORTFOLIO_EMPTY` |
| 持仓行标的缺失 | `HOLDING_INSTRUMENT_MISSING` |
| 数量为 0 或非有限值 | `HOLDING_QUANTITY_INVALID` |
| 同一标的重复持仓行 | `DUPLICATE_HOLDING` |
| 快照列表缺失/为空 | `SNAPSHOT_LIST_MISSING` / `SNAPSHOT_LIST_EMPTY` |
| 快照日期缺失 | `SNAPSHOT_DATE_MISSING` |
| **重复日期快照**（不自动去重） | `DUPLICATE_SNAPSHOT` |
| 快照价格表缺失 | `SNAPSHOT_PRICES_MISSING` |
| 最新快照缺少某持仓标的价格 | `MISSING_PRICE_FOR_INSTRUMENT` |
| 最新价为空值/非有限/非正 | `PRICE_NON_POSITIVE` |
| 时序乱序 | **不拒绝**：按日期升序重排，`notes` 标注，摘要对乱序不敏感 |

### 2.2 场景

| 情形 | 拒绝码 |
|---|---|
| 场景/ID/版本/类型缺失 | `SCENARIO_MISSING` / `SCENARIO_ID_MISSING` / `SCENARIO_VERSION_MISSING` / `SCENARIO_TYPE_MISSING` |
| 冲击表缺失/为空 | `SHOCK_MAP_MISSING` / `SHOCK_EMPTY` |
| 冲击幅度非有限值 | `SHOCK_VALUE_NONFINITE` |
| 冲击引用未持有标的 | `SHOCK_REFERENCES_UNKNOWN_INSTRUMENT` |
| 相关矩阵非方阵/维度不符 | `CORRELATION_NOT_SQUARE` / `CORRELATION_DIMENSION_MISMATCH` |
| 相关标的缺失/重复/未持有 | `CORRELATION_INSTRUMENTS_MISSING` / `..._DUPLICATE_INSTRUMENT` / `..._INSTRUMENT_NOT_HELD` |
| 元素非有限 / 对角≠1 / 非对称 | `CORRELATION_VALUE_NONFINITE` / `..._DIAGONAL_INVALID` / `..._NOT_SYMMETRIC` |
| **非对称半正定**（Cholesky 失败） | `CORRELATION_NOT_PSD` |
| 蒙特卡洛抽样次数非正 | `MC_SAMPLE_COUNT_INVALID` |
| 抽样/历史样本数 < 20 | `TAIL_SAMPLES_INSUFFICIENT` |
| 估计波动率的有效快照不足（<3 期） | `VOLATILITY_SAMPLES_INSUFFICIENT` |
| 历史序列缺失/为空 | `HISTORICAL_RETURNS_MISSING` / `HISTORICAL_SERIES_EMPTY` |
| 历史序列缺少持仓标的 | `HISTORICAL_INSTRUMENT_MISSING` |
| 序列长度不一致 / 含非有限值 | `HISTORICAL_SERIES_LENGTH_MISMATCH` / `HISTORICAL_RETURN_NONFINITE` |

> 尾部样本下限统一为 **20**（95%/99% 口径一致），不足即拒绝，不输出没有统计意义的尾部指标。

### 2.3 三种结论状态

| status | 含义 | 是否可重试 | 是否计入样本 |
|---|---|---|---|
| `ACCEPTED` | 正常结论，含指标 | 否（确定结论，幂等复用） | `sampleCount` |
| `REJECTED` | 数据/口径被明确拒绝，含原因码与说明 | 否（重算仍是同一结论） | 0 |
| `FAILED` | 协议/技术问题（超时、节点异常、畸形 JSON） | 是（可重新提交） | 0 |

---

## 3. 并发与重试语义

- **隔离**：每个试算在弹性 cached 线程池中独立运行（外层重试等待嵌套提交，固定池会自我死锁）；单个试算长耗时（超时）、抛异常，只在自身结果上体现
  `FAILED`，不阻塞、不污染同批或其他试算（见 `TrialExecutionServiceTest.timeoutDoesNotBlockOthers`）。
- **超时**：每轮尝试通过 `Future.get(timeoutMs)` 控制（超时后 `cancel(true)` 精确中断计算线程，旧慢任务不会泄漏或重复计入）（默认 5000ms，请求可覆盖）。
- **幂等去重**：以 `requestId` 为键，`ConcurrentHashMap.computeIfAbsent` 保证
  **并发重复提交只注册一次计算**，所有调用方拿到同一个结果；顺序重复提交直接复用缓存结论。
- **重试**：仅技术失败（异常/超时）在同一试算内重试，最多 `maxAttempts` 次（默认 2）；
  结果中 `attempt` 记录实际尝试次数。`ACCEPTED/REJECTED` 是确定结论，不重试。
- **不重复计入**：样本计数只来自唯一一次被接受的计算；失败/拒绝计数为 0；
  重试成功时指标只反映最后一次成功计算。
- **FAILED 不固化**：技术失败后缓存条目被移除，相同 `requestId` 可在节点恢复后重新提交；
  正常/拒绝结论保留，供重复提交与 `GET` 查询幂等返回。
- **可追溯**：每个正常/拒绝结果带 `inputDigest`（对排序后输入做 SHA-256 截断 16 位）、
  `scenarioVersion`、`sampleCount`、`attempt` 与 `notes`。

---

## 4. HTTP 接口

业务结论（ACCEPTED/REJECTED/FAILED）统一以 **HTTP 200 + 结果体** 返回，由 `status` 区分；
畸形 JSON 返回 HTTP 400 + FAILED；未预期异常 HTTP 500 + FAILED（不含堆栈）。

| 方法与路径 | 说明 |
|---|---|
| `POST /api/risk/trials` | 提交单个试算（同步返回结论） |
| `POST /api/risk/trials/batch` | 批量试算（并发执行、相互隔离，返回顺序=请求顺序） |
| `GET  /api/risk/trials/{requestId}` | 查询幂等结论；不存在/未完成返回 404 |
| `GET  /api/risk/health` | 健康检查 |

请求体关键字段：

```json
{
  "requestId": "t-001",
  "timeoutMs": 5000,
  "maxAttempts": 2,
  "portfolio": {
    "portfolioId": "PF-1",
    "holdings": [
      {"instrumentId": "A", "quantity": 100},
      {"instrumentId": "B", "quantity": -50}
    ]
  },
  "snapshots": [
    {"snapshotDate": "2026-09-01", "prices": {"A": 100.0, "B": 20.0}},
    {"snapshotDate": "2026-09-02", "prices": {"A": 100.2, "B": 20.05}}
  ],
  "scenario": {
    "scenarioId": "SHOCK-1",
    "version": "2026-09-shock-v1",
    "type": "SCENARIO_SHOCK",
    "shocks": {"A": -0.10, "B": 0.05}
  }
}
```

相关性场景使用 `type=CORRELATION_ADJUST`，并提供
`correlationInstruments`（行列标的顺序）、`correlation`（方阵）、`seed`、`sampleCount`；
历史场景使用 `type=HISTORICAL_SIMULATION`，并提供 `historicalReturns`（标的 → 等长收益率序列）。

---

## 5. 本地验证方法

### 5.1 运行测试（推荐入口，离线可执行）

```bash
mvn test            # 56 个用例：矩阵/统计/场景/校验/确定性/并发重试/Web 端到端
```

测试日志会打印每个用例的**输入摘要、场景版本、判定依据与拒绝原因**（`CALC-*` / `TRIAL-*` 前缀）。

重点边界覆盖：

- 并发重复提交（16 线程同 requestId 仅计算一次、样本计数不重复计入）；
- 数据缺失、重复持仓/重复快照、时序乱序归一化；
- 非方阵、非对称、对角非法、非 PSD 相关矩阵；
- 历史 19 期 / 蒙特卡洛 19 次抽样的尾部样本不足拒绝；
- 单试算超时不阻塞同批其他试算；技术异常 FAILED 与业务 REJECTED 明确可区分；
- 同种子蒙特卡洛逐点复现、乱序输入摘要一致、跨版本结论标注不可比。

### 5.2 启动服务并手工调用

```bash
mvn spring-boot:run          # 默认随机端口；如需固定端口，改 application.properties 的 server.port
```

```bash
curl -s -X POST http://localhost:8080/api/risk/trials \
  -H 'Content-Type: application/json' \
  -d @sample-request.json
```

正常结论示例字段：`status=ACCEPTED, scenarioVersion, baseValue, stressedPnl,
var95, es95, var99, es99, sampleCount, inputDigest, notes, attempt`；
被拒绝时为 `status=REJECTED, rejectCode, rejectReason, sampleCount=0`。

---

## 6. 代码结构

```
model/      持仓/组合/快照/场景/结论/原因码等不可变类型
engine/     ValuationEngine（估值）、RiskStatistics（VaR/ES）、
            MatrixValidator（矩阵合法性）、ScenarioSimulator（三类场景）
service/    InputValidationService（校验归一化）、RiskCalculationService（确定性计算）、
            TrialExecutionService（并发/超时/重试/幂等）
web/        RiskController（REST）、ApiExceptionHandler（协议层错误口径）
util/       InputDigest（归一化输入摘要）
```
