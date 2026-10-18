# DishFlow

门店点餐与经营管理系统，Java 后端，基于 Spring Boot 的 Maven 多模块单体架构。

除常规业务（菜品运营、下单、订单履约、报表）外，项目的两个工程重点是：

- **下单主链路的事务边界**——外部调用（大模型解析）移出事务，纯写库部分独立成 bean 并加事务，避免连接池被长耗时请求占满
- **大模型能力接入的可靠性**——超时与异常分类、降级兜底、输出白名单校验，且这三处都有可复现的评测或测试

与多数演示项目不同，AI 能力附带了 **59 条标注样本的评测集** 和 **6 个测试类**，结果可当场重跑验证。

---

## 快速开始

本项目默认使用 Mock 实现，**无需任何 API Key 或商户号即可完整运行**：

- 大模型：`sky.ai.provider` 默认 `mock`，走离线关键词规则，不联网、不花钱
- 支付：`sky.pay.channel` 默认 `mock`，调用即返回成功，不需要商户号

```bash
# 1. 准备数据库
#    仓库内 sql/ai-module.sql 只包含 AI 模块的增量变更（orders.ai_tags 列 + ai_call_log 表），
#    不含基础建表语句。完整表结构需从本地数据库导出：
mysqldump -uroot -p --no-data <你的库名> > sql/schema.sql
mysql -uroot -p <你的库名> < sql/schema.sql
mysql -uroot -p <你的库名> < sql/ai-module.sql   # 增量脚本，重复执行安全

# 2. 复制配置模板并填入本机连接
cd dishflow-server/src/main/resources
cp application-dev.yml.example application-dev.yml
# 编辑 application-dev.yml，至少填 datasource 与 redis

# 3. 编译并启动
mvn clean package -DskipTests
java -jar dishflow-server/target/dishflow-server-1.0-SNAPSHOT.jar
```

> **注意**：`sql/` 目录目前只有 AI 模块的增量脚本。基础表（`orders`、`dish`、`category` 等）的建表语句需按上面第 1 步从本地库导出后一并提交，否则克隆后无法直接启动。

启动后访问接口文档：`http://localhost:8080/doc.html`（Knife4j）

想启用真实大模型：将 `application-dev.yml` 里的 `sky.ai.provider` 改为 `deepseek` 并填入自己的 `api-key`。

> `application-dev.yml` 已在 `.gitignore` 中，不会被提交，密钥不会泄露。

---

## 模块划分

```
dishflow-common   常量、统一异常与返回值、JWT/OSS 工具类、AI 客户端抽象
dishflow-pojo     entity / dto / vo
dishflow-server   controller / service / mapper / config / payment / websocket / task
```

拆分依据：

- `common` 与 `pojo` 不依赖 Spring Web，便于被其他服务复用
- 业务代码只依赖 `LlmClient`、`PayChannel` 这类**接口**，具体实现由配置在启动时决定：

```java
@ConditionalOnProperty(name = "sky.ai.provider", havingValue = "deepseek")
@ConditionalOnProperty(name = "sky.ai.provider", havingValue = "mock", matchIfMissing = true)
@ConditionalOnProperty(name = "sky.pay.channel", havingValue = "wechat")
@ConditionalOnProperty(name = "sky.pay.channel", havingValue = "mock", matchIfMissing = true)
```

同一套「接口 + 条件注入」模式在 AI 与支付两处复用，`matchIfMissing = true` 保证刚 clone 下来不配任何密钥也能跑通。

---

## 三处关键设计

### 一、下单主链路的事务边界

**问题**

下单需要写订单主表、批量写明细、清空购物车，这三步必须原子。但下单前还要调用大模型解析备注，这是一次最长 1.5 秒的外部 HTTP 请求。

如果把大模型调用包进 `@Transactional`：Spring 在方法进入时就从 Druid 连接池借出了连接，这 1.5 秒连接被空占。高并发下连接池耗尽后，**整个服务（包括与 AI 无关的接口）都会卡死**。

**方案**

把职责拆成两层：

| 位置 | 职责 | 是否带事务 |
|---|---|---|
| `OrderServiceImpl.submit` | 编排：校验 → 调 AI 解析备注 → 提交事务 | **不加** |
| `OrderSubmitTxService.createOrder` | 纯数据库写：订单 → 明细 → 清购物车 | **加** `@Transactional` |

`OrderSubmitTxService` 里不允许出现任何外部调用。

**取舍**

没有直接在 `submit` 上加 `@Transactional` 然后"把 AI 调用挪到最前面"——那样事务仍然覆盖了外部调用，问题没解决。

单独拆 bean 而不是拆成同类私有方法，是因为 **`@Transactional` 依赖 Spring 代理生效，同类内部方法互调不走代理，事务会静默失效**。这是拆 bean 的第二个理由。

**结果**

`OrderSubmitFlowTest` 端到端验证：2 条购物车记录生成 2 条订单明细、下单后购物车被清空、AI 标签确实写入 `orders.ai_tags` 字段。另有测试验证**空备注不触发模型调用**（只留一条 `skipped` 流水），避免无效成本。

---

### 二、大模型解析的降级链路

**问题**

模型接口会超时、限流、突发不可用，而这条链路挂在用户下单主流程上。任何阻塞都会直接影响下单。

**方案**

1. 调用移出事务（见上一节）
2. 超时三层都设：`setConnectTimeout` / `setConnectionRequestTimeout` / `setSocketTimeout` 分别配置
3. **按异常类型决定是否重试**：超时、连接失败、5xx 属于瞬时故障，重试一次有意义；4xx（key 错、参数错、配额耗尽）重试只会再失败一次并**再花一笔钱**，直接降级
4. 降级后走规则引擎兜底解析，保证下单不受影响
5. 每次调用（含跳过、降级）都写入 `ai_call_log` 流水，便于事后排查与重放

**取舍**

**超时类错误不做多次重试。** 重试会放大下游压力并延长用户等待，只给一次机会后立即降级。

选择「降级到规则解析」而不是「让用户重填备注」，因为**可用性优先于解析准确率**——备注解析失败不影响订单本身正确性。

**结果**

`AiDegradationTest` 用 `sky.ai.base-url=http://127.0.0.1:1` 构造一个必然连不上的地址，不依赖真实故障注入，验证三件事：

| 用例 | 断言 |
|---|---|
| 备注解析降级 | 不抛异常、返回对象不为 null（调用方无需判空）、五个字段全为 `unknown`、`hasAnyHint()` 为 false（降级结果不会被当成有效标签落库） |
| 空备注短路 | 流水状态是 `skipped` 而不是 `timeout`，确认省调用真的生效 |
| 经营洞察降级 | 返回 `degraded=true` 且**带上 `degradedReason`**，不允许"装作有结论" |

此外断言**降级必须留下流水记录**（原文注释：*否则线上无法发现 AI 在静默失败*）。

---

### 三、输出白名单校验（拦截数值幻觉）

**问题**

大模型在缺少约束时会自行编造具体数字。经营洞察场景下如果直接采信，会给门店管理者错误结论。

**方案**

分三层，但**重心在第三层**：

| 层 | 做法 | 作用 |
|---|---|---|
| 输入侧 | 长度截断 + 字符白名单 + PII 脱敏（`\d{7,}` 掩码） | 缩小攻击面，同时控制成本 |
| 分隔 | 用户内容用 `<user_input>` 标签包裹，与指令分离 | 让模型能区分"资料"和"命令" |
| **输出侧** | **强制 JSON Schema 解析 + 枚举白名单 + 数值校验** | **真正的安全边界** |

核心约束是「**模型不参与计算**」：所有数字先由 SQL 统计出来再喂给模型，模型只做解释和建议。输出后做数值校验，出现输入中未提供的数字则整条丢弃。

**取舍**

前两层只能降低被注入的概率，**输出必须落在合法集合内**才是可保证的边界，所以校验重心放在输出侧。

**结果**

对照实验（`docs/ai-eval/insight-eval-report.txt`）：不加数值约束时，模型在经营洞察中输出了 **8 个输入中根本不存在的数字**；加入数字白名单校验后全部拦截。

---

## 评测方法与结果

备注解析的评测集位于 `dishflow-server/src/test/java/com/dishflow/AiRemarkEvalTest.java`，共 **59 条标注样本**，覆盖辣度、忌口、餐具、配送、加急五个维度，其中包含 **3 条提示词注入样本**和 **3 条空/无意义样本**，用来验证模型不会乱填。

| 维度 | 准确率 |
|---|---|
| 辣度 spicy | 51 / 51 = **100%** |
| 忌口 noIngredients | 45 / 46 = **97.83%** |
| 餐具 utensil | 53 / 53 = **100%** |
| 配送 delivery | 50 / 51 = **98.04%** |
| 加急 urgent | 51 / 51 = **100%** |

**标注原则（诚实优先于好看）**

- 只有语义唯一确定的样本才标具体期望值；有歧义的（如"辣度随意，别太辣"）一律标 `ANY`，**不参与准确率计算**
- 用户未提到的字段期望值为 unknown，对应提示词里"禁止推测"的规则
- 包含注入样本与空样本，用于验证不会乱填

**失败样本**

2 条已记录归因，属于「模型行为与标注规范不一致」而非解析错误：

- `葱花香菜都不要` → 期望 `{}`，实际解析出 `noIngredients: [葱花, 香菜]`
- `放保安室` → 期望 `{}`，实际 `deliveryNote: unknown`

完整明细见 `docs/ai-eval/`（`remark-eval-summary.txt` / `remark-eval-detail.csv` / `insight-eval-report.txt`）。

**运行方式**

评测默认关闭（`@EnabledIfEnvironmentVariable`，需 `AI_EVAL=true`），因为每次运行都会产生真实的大模型调用费用：

```bash
set AI_EVAL=true && mvn -pl dishflow-server test -Dtest=AiRemarkEvalTest
```

---

## 测试

| 测试类 | 覆盖内容 |
|---|---|
| `OrderSubmitFlowTest` | 下单主链路：明细写入、购物车清空、AI 标签落库、空备注跳过模型 |
| `PaymentFlowTest` | Mock 支付结算、**重复支付幂等**（`paySuccessIsIdempotent`）、拦截重复及他人订单支付 |
| `PayChannelSelectionTest` | 配置为 `wechat` 时容器注入 `WeChatPayChannel`（条件注入生效） |
| `AiDegradationTest` | 模型不可用时：解析降级、空备注短路、洞察返回 degraded 且带原因、降级留流水 |
| `AiRemarkEvalTest` | 备注解析评测集（59 条） |
| `AiInsightEvalTest` | 经营洞察评测 |

> `OrderSubmitFlowTest`、`AiRemarkEvalTest`、`AiInsightEvalTest` 需要真实模型调用或依赖本地测试数据（`user id=4`、地址簿 `id=2`、菜品 `46/47` 起售），运行前需按 `sql/` 目录初始化并准备对应数据。

---

## 已知限制

- **单体架构**，未做服务拆分；无注册中心、无分布式事务
- **下单接口未做提交幂等**：同一用户重复点击理论上可能产生重复订单。幂等目前只做在支付回调一侧——`PaymentFlowTest` 的 `paySuccessIsIdempotent` 验证了重复支付不会重复结算，`rejectRepeatedAndForeignPayment` 验证了重复及他人订单的支付请求会被拦截；下单入口的幂等键与唯一索引约束尚未实现
- **微信支付仅完成接入骨架**：已抽象 `PayChannel` 接口并支持条件切换，但个人主体无法申请商户号，默认走 `MockPayChannel`
- **接口文档注解覆盖不全**：`@ApiOperation` 目前覆盖约 29% 的接口，其余仅靠自动生成的参数结构
- **部分测试依赖本地数据**，未做到完全自包含
- **大模型模块为借助 AI 工具生成后逐行阅读并重构**：设计意图（事务边界、异常分类、输出校验）经过重新梳理，代码仍在持续理解与优化中
