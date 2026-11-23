# 本体平台 · 后续开发规划

> 基于当前 MVP（`ontology-mvp`，6 个提交，25 个测试，54 项 demo 断言）的演进计划。
> 目标不是堆功能，是把"带外键的对象表"变成"能问复杂问题、能存答案、能交给别人用的知识层"。

---

## 0. 实施状态（2026-11-23 完成）

| 阶段 | 任务 | 状态 | 提交 |
|---|---|---|---|
| 0 | T0.1 链接墓碑 | ✅ 已交付 | `5475fe6` |
| 0 | T0.2 删除检测（stale） | ✅ 已交付 | `20da06f` |
| 0 | T0.3 采集后自动 ANALYZE | ✅ 已交付 | `5475fe6` |
| 0 | T0.4 包拆分 store/query/web | ✅ 已交付 | `63e872e` |
| 0 | T0.5 前端 ES module | ⚠️ 部分交付 | `56c46ab` |
| 1 | T1.1~T1.4 多跳遍历 | ✅ 已交付 | `e2092bb` |
| 2 | T2.1~T2.4 聚合 | ✅ 已交付 | `3803d35` |
| 3 | T3.1~T3.5 对象集 | ✅ 已交付 | `44c8cbf` |

测试从 25 个增至 **44 个**，全部通过。

### 与原计划的偏差（重要）

| 项 | 计划 | 实际 | 原因 |
|---|---|---|---|
| T0.5 | app.js 按 view 彻底拆分 | 只抽出 `core.js`（工具/会话/state/api），app.js 仍 1167 行 | 新增功能都是独立 module（traverse/aggregate/objectset），app.js 不再膨胀，目的已达成；彻底拆分收益低于风险 |
| T2.2 | 元模型层拒绝多值属性 group by | 改为查询时校验（400） | 保存时拒绝会连带禁止"多值属性可用于其他场景"，限制过宽；查询时拒绝更精确 |
| T3.3 | 集合级授权（细粒度 ACL） | 实现 owner + shared 两档 | 当前只有三个角色，细粒度 ACL 没有使用场景 |
| 遍历 | 计划支持 limit 参数 | 已支持，且每跳上限硬编码 100 | 大 limit 会让前端一次渲染上千节点，没有意义 |

### 实测数据（供后续规划参考）

| 指标 | 20 万对象 |
|---|---|
| 采集 | 383~412 s（485~537 对象/秒，逐行提交是瓶颈） |
| 首页查询（DB 层） | 0.03 ms |
| 首页查询（端到端） | 86 ms（其中 83 ms 是 HTTP Basic 的 BCrypt） |
| 聚合（按 1 属性分组） | 160~280 ms |
| 深翻页（第 100 页） | 80 ms，不退化 |
| 存储 | obj_object 53 MB + obj_prop 216 MB |

---

## 1. 范围与目标

### 1.1 本期的三件事

| 能力 | 解决什么问题 | 为什么排前面 |
|---|---|---|
| **多跳遍历** | 现在只能一跳，「延误 → 航班 → 航司」要手动调三次 | 本体区别于关系表的唯一硬指标 |
| **聚合** | 现在只能 count，永远只能"看列表" | 分析场景的入口 |
| **对象集** | 每次查询都是临时的，查完就没了 | 让查询变成可复用、可授权的资产 |

### 1.2 本期不做的

| 功能 | 理由 |
|---|---|
| Function（对象上跑任意代码） | 需要容器隔离/超时/资源限制，工程量 2-3 周，安全风险高；声明式派生已覆盖大部分场景 |
| CDC 实时同步 | 当前数据量下是过度设计，且源表无 `updated_at`，增量本身做不了 |
| 行级权限 / 多租户 | 无多客户场景 |
| 时间旅行（对象历史快照） | `act_edit` 已有变更流水，除非有合规要求 |
| 可视化建模画布 | 页面配置已够用，纯 UI 投入 |

---

## 2. 架构演进

### 2.1 原则：保持单体，内部严格分区

不做微服务拆分——当前规模（6 个类型、20 万对象、单库）完全不需要，拆了只会增加运维成本。
真正的问题是**包内职责混杂**：`objectdata` 现在 8 个类，`ObjectQueryService` 同时管参数校验、权限、SQL 构建、属性装配。

### 2.2 后端包结构

```
objectdata/
├── store/     ObjectStore · ObjectRow · LinkRow
│              职责：一行对象/属性/链接怎么读写（纯 SQL，无业务判断）
├── query/     ObjectQueryService · ObjectQuerySqlBuilder · LinkQueryService
│              TraversalService · AggregateService · ObjectSetService   ← 新增
│              职责：请求怎么翻译成 SQL，权限怎么裁剪
└── web/       ObjectController · ObjectSetController                    ← 新增
               职责：HTTP 参数校验、异常映射
```

拆分后单文件控制在 400 行内。`ObjectStore` 不感知元模型，`query` 不直接拼 SQL 字符串。

### 2.3 前端结构

现状 `app.js` 1216 行 + `meta.js` 800 行，再加三块功能会破 3000 行。
改用原生 ES module（`<script type="module">`），**不引入构建链**：

```
static/
├── core/    api.js · session.js · toast.js · dom.js
├── views/   browse.js · detail.js · action.js · meta.js
│            traverse.js · aggregate.js · objectset.js          ← 新增
└── main.js  装配与视图切换
```

好处：浏览器原生 `import`，`mvn spring-boot:run` 直接跑，不需要 npm。

### 2.4 数据库改动（最小集）

```sql
-- 阶段 0
alter table obj_link   add column deleted_by_action boolean not null default false;
alter table obj_object add column last_batch bigint;
alter table obj_object add column stale boolean not null default false;

-- 阶段 3
create table obj_set (
    id          varchar(64) primary key,
    name        varchar(120) not null,
    type_code   varchar(80) not null,
    query_json  jsonb not null,          -- 只存条件，不存结果
    owner       varchar(80) not null,
    shared      boolean not null default false,
    created_at  timestamptz not null default now()
);
```

---

## 3. 任务分解（WBS）

### 阶段 0 · 止血与重构（3 天）

| ID | 任务 | 角色 | 依赖 | 产出 / 验收 |
|---|---|---|---|---|
| T0.1 | `obj_link.deleted_by_action`，采集时跳过 | 后端 B | — | Action 删的链接重采不复活 |
| T0.2 | `last_batch` + `stale` 标记，控制台灰显 | 后端 B | — | 上游删一行 → 重采 → 对象变灰 |
| T0.3 | 采集后自动 `ANALYZE` | 后端 B | — | 20 万对象首页查询稳定在毫秒级 |
| T0.4 | 包结构拆分 `store / query / web` | 后端 A | — | 编译通过，25 个测试全绿 |
| T0.5 | 前端拆 ES module | 前端 | — | 行为不变，手工过一遍 6 个教程任务 |

### 阶段 1 · 多跳遍历（2 天）

| ID | 任务 | 角色 | 依赖 | 产出 / 验收 |
|---|---|---|---|---|
| T1.1 | `POST /api/objects/{id}/traverse` | 后端 A | T0.4 | 深度 ≤3，路径按跳返回 |
| T1.2 | 遍历权限：每跳校验目标类型可读 | 后端 A | T1.1 | viewer 走不到不可读类型，返回 403 |
| T1.3 | 遍历前端：路径面包屑 + 可点击 | 前端 | T1.1 | 从 Delay 走到 Airline，三跳可见 |
| T1.4 | 遍历测试 | QA | T1.1 | 深度越界 400 / 结果超限截断 / 权限 403 |

**API 契约**

```json
POST /api/objects/Delay:D001/traverse
{
  "path": [
    {"linkType": "DelayedBy",  "direction": "IN"},
    {"linkType": "OperatedBy", "direction": "OUT"}
  ],
  "limit": 50
}
```

**硬约束**（不满足就等着被拖垮）：
- 深度 ≤ 3
- 每跳中间结果 ≤ 100
- 总结果 ≤ 1000，超出截断并返回 `truncated: true`
- 重复路径按 `object_id` 去重

### 阶段 2 · 聚合（2 天）

| ID | 任务 | 角色 | 依赖 | 产出 / 验收 |
|---|---|---|---|---|
| T2.1 | `POST /api/objects/aggregate` | 后端 A | T0.4 | group by + count/sum/avg/max |
| T2.2 | 元模型层拒绝：多值属性不能 group by | 后端 A | T2.1 | 保存定义时就报错，不是运行时 |
| T2.3 | 聚合前端：表格 + 柱状 | 前端 | T2.1 | 按出发机场统计平均延误 |
| T2.4 | 聚合性能基线 | QA | T2.1 | 20 万对象 P95 < 500ms |

**API 契约**

```json
POST /api/objects/aggregate
{
  "typeCode": "Flight",
  "groupBy": ["departure"],
  "metrics": [
    {"fn": "count"},
    {"fn": "avg", "prop": "delayMinutes"},
    {"fn": "max", "prop": "delayMinutes"}
  ],
  "limit": 200
}
```

**语义约定**：`NULL` 单独成组（"未填"本身是有意义的分类）；`groupBy` 属性必须是单值且 `owner != derived`。

### 阶段 3 · 对象集（3 天）

| ID | 任务 | 角色 | 依赖 | 产出 / 验收 |
|---|---|---|---|---|
| T3.1 | `obj_set` 表 + CRUD | 后端 B | T0.4 | 保存/列表/删除 |
| T3.2 | 查询支持 `objectSetId` | 后端 A | T3.1 | 用集合当查询源，与 filters 互斥 |
| T3.3 | 集合级授权 | 后端 B | T3.1 | owner 可改 shared；非 owner 只读 |
| T3.4 | 集合管理界面 | 前端 | T3.1 | 从查询结果一键保存 |
| T3.5 | 回归 + 文档 | 全员 | 全部 | `demo.sh` 扩到 70+ 断言，README 更新 |

---

## 4. 分工方案

### 4.1 1 人（串行）

```
T0.1 → T0.2 → T0.3 → T0.4 → T0.5 → T1.1 → T1.3 → T2.1 → T2.3 → T3.1 → T3.2 → T3.4
```

约 **10 个工作日**。适合当前状态（我一个人的场景）。

### 4.2 3 人（后端 2 + 前端 1）

| 角色 | 负责 | 工作量 |
|---|---|---|
| **后端 A（查询引擎）** | T0.4 → T1.1 → T1.2 → T2.1 → T2.2 → T3.2 | 5 天 |
| **后端 B（存储/接入）** | T0.1 → T0.2 → T0.3 → T3.1 → T3.3 | 3.5 天 |
| **前端** | T0.5 → T1.3 → T2.3 → T3.4 | 4.5 天 |

**关键路径在后端 A**：三个核心 API 全压在他身上。缓解办法是把 T3.2（集合查询）交给 B——集合查询本质是"把 filters 换成查表拿条件"，不涉及复杂 SQL。

### 4.3 4 人（+ QA）

QA 从**第 1 周就介入**，先按 API 契约写测试（此时接口还没实现），实现完直接跑：

- T1.4 / T2.4 提前到对应 API 定稿时
- 负责 `demo.sh` 扩展（每个新能力至少 3 条断言）
- 负责性能基线：遍历深度 vs 延迟、聚合在 20 万对象上的 P95

并行后总周期约 **2.5 周**（含缓冲）。

---

## 5. 依赖与风险

### 5.1 依赖图

```
T0.4 包拆分 ──┬── T1.1 traverse ── T1.2 权限
              ├── T2.1 aggregate ── T2.2 多值校验
              └── T3.2 objectSet 查询

T3.1 obj_set 表 ── T3.2 / T3.3 / T3.4
T0.1 墓碑 ── (独立)
T0.2 删除检测 ── (独立)
```

T0.4 是所有查询类任务的前置，**必须先做**，否则新增的类会继续堆在 `objectdata` 根目录。

### 5.2 风险清单

| 风险 | 影响 | 缓解 |
|---|---|---|
| **遍历结果爆炸** | 深度 3 × 每跳 100 = 百万级路径，内存打爆 | 硬限制深度/每跳/总数，超出截断并标记 |
| **聚合语义歧义** | 多值属性 group by 会让一个对象出现在多组，统计翻倍 | 元模型保存时就拒绝，不留到运行时 |
| **前端拆模块引入回归** | 拆完点不出来 | 拆完先跑 25 个测试 + 手过 6 个教程任务，再动新功能 |
| **对象集绕过权限** | 以为存了集合就能看全部属性 | 集合只存条件，查询时仍走属性级 ACL 裁剪 |
| **采集和分析抢连接** | 采集占满 12 个连接池，聚合请求排队 | 已有采集互斥锁；聚合加 5s 超时 |

---

## 6. 验收标准

| 阶段 | 可验证的结果 |
|---|---|
| 0 | 重采后 Action 删的链接不复活；上游删一行后对象变灰；20 万对象首页查询 <10ms（DB 层） |
| 1 | 一条请求查出 `Delay:D001 → Flight → Airline`；深度 4 返回 400；viewer 访问不可读类型返回 403 |
| 2 | 按 `departure` 分组统计平均延误；20 万对象 P95 <500ms；多值属性 group by 在保存定义时被拒 |
| 3 | 保存集合 → 用它查询 → viewer 只能看到 shared 集合；`demo.sh` 通过 70+ 断言 |

---

## 7. 之后的下一站（不做，但记下来）

- **Function**：对象上的计算（"这架飞机的利用率"），需要沙箱
- **订阅/告警**：对象变化时推事件
- **Action 副作用**：执行后触发 webhook / 工单
- **数据血缘**：属性来自哪张表哪一列（元模型已有 `sourcePath`，缺可视化和影响分析）
- **增量采集**：等上游加 `updated_at`
