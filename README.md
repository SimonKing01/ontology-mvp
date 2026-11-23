# 本体（Ontology）服务 MVP

**本体 = 业务对象的读模型 + 唯一写路径。**

它把上游表映射成"业务对象"（可查询、可被 Action 操作），把业务状态的写入收敛到 Action 一条路径上。
它**不是 ORM**：没有 `@Entity class Customer`，也没有 `PUT /api/objects/{id}`。

三条判定标准贯穿全部代码：

1. **对象类型是"数据源映射"**：`ObjectType` = `datasetId + idField + titleField + properties[]`（每个属性有 `sourcePath`），映射到一张物理表/视图；
2. **对象数据默认不落第二份**：查询直接下推到底层数据源投影（`obj_object`/`obj_prop`），只有"本体拥有（ontology-owned）"的状态存在本服务的表里；
3. **所有写操作必须经过 Action**：没有第二条写路径（包括内部 Service 方法与调试接口）。

---

## 1. 快速开始

```bash
# 1) 起一个 PostgreSQL 16（端口 55434，库 ontology/ontology）
docker compose up -d

# 2) 起应用（Flyway 自动建表 + 灌入演示用的"上游" schema airline_domain）
mvn spring-boot:run

# 3) 另开终端，跑端到端演示（curl 全链路，逐步断言）
./demo.sh
```

- 应用：<http://localhost:8080>，Swagger：<http://localhost:8080/swagger-ui.html>
- 账号（HTTP Basic）：`admin/admin`（ADMIN）、`editor/editor`（EDITOR）、`viewer/viewer`（VIEWER）
- 演示库需要是空的。要重跑：

```bash
docker compose down -v && docker compose up -d && mvn spring-boot:run && ./demo.sh
```

`demo.sh` 最后会打印 `通过 N 项，失败 0 项`，并覆盖：建源 → 建数据集 → 建对象类型（含 revision）→ 采集 → 重采（ID 稳定）→ 查询（下推 + keyset）→ 一跳遍历 → 4 个 Action → 幂等重放 → 版本冲突 → 前置条件 → 审计 → 越权与属性裁剪。

---

## 2. 架构

```
                    ┌─────────────── 上游系统（只读）────────────────┐
                    │ airline_domain.airport / flight / aircraft …   │
                    └───────────────────────┬───────────────────────┘
                                            │ 全量采集（只写 source / derived 属性）
                                            ▼
┌────────────┐   元模型定义      ┌──────────────────────────────────────┐
│  客户端     │─────────────────▶│ ont_definition (+ ont_definition_history)
│ ADMIN      │   POST/PUT       │ ds_source / ds_dataset               │
│ EDITOR     │                  └──────────────────────────────────────┘
│ VIEWER     │
│            │   查询（只读）    ┌──────────────────────────────────────┐
│            │─────────────────▶│ obj_object / obj_prop / obj_link     │
│            │  POST /query     │ 属性过滤 → EXISTS 子查询（SQL 下推）  │
│            │                  └──────────────────────────────────────┘
│            │   Action（写）    ┌──────────────────────────────────────┐
│            │─────────────────▶│ ActionEngine —— 唯一写路径            │
└────────────┘                  │ 幂等 → 权限 → 参数 → 行锁 → 版本 →    │
                                │ 前置条件 → 变更 → act_run/act_edit    │
                                └──────────────────────────────────────┘
```

- **读**：只碰 `obj_*`，过滤/排序/分页全部在 SQL 里完成；
- **写**：只有 `ActionEngine` 能改 `obj_prop` 里 ontology-owned 的部分；
- **采集**：只写 source/derived 属性，不触碰 ontology 状态，所以不算第二条写路径；
- **元模型**：启动时全量校验并加载成内存快照，任何不合法（尤其"Action 改 source 属性"）会让应用启动失败。

目录：

```
src/main/java/com/example/ontology/
├── meta/          元模型：定义、版本、校验、注册表、REST
├── datasource/    数据源/数据集、连接工厂、全量采集（对象/链接/派生）
├── objectdata/    对象层：ObjectStore（唯一数据访问）、查询下推、keyset、一跳遍历
├── action/        Action 引擎、表达式、前置条件、审计
├── security/      类型/属性/Action 三层授权
└── common/        错误码、异常、类型映射、游标、SQL 标识符校验
src/main/resources/db/migration/
├── V1__init.sql          本体表（含全部索引与约束）
└── V2__demo_source.sql   演示用上游 schema 与数据
```

---

## 3. 设计决策表（9 条铁律 → 实现位置）

| # | 铁律 | 实现方式 | 位置（文件:类:行） |
|---|---|---|---|
| R1 | 对象类型 = 数据源映射 | `ObjectTypeDef` 是普通 record（无 `@Entity`），靠 `datasetId` + 每个属性的 `sourcePath` 映射到物理表；数据库里只有元模型定义，没有"对象表" | `meta/model/ObjectTypeDef.java:ObjectTypeDef:16` |
| R2 | 读不复制数据 | 查询直接读 `obj_object`/`obj_prop` 投影；采集只写 source/derived 属性，ontology 状态由 Action 独占写入 | `objectdata/ObjectQueryService.java:ObjectQueryService.query:62`、`datasource/IngestService.java:doIngestObjectType:152` |
| R3 | Action 是唯一写入口 | 全仓库只有 `ActionEngine` 调 `ObjectStore.setProperty/insertLink`；`/api/objects/**` 没有任何 PUT/PATCH/DELETE | `action/ActionEngine.java:ActionEngine.executeInTx:78` |
| R4 | 查询必须下推为 SQL | 每个属性过滤翻译成 `obj_prop` 的 `EXISTS` 子查询（同属性多条件用不同别名），排序翻译成 `LEFT JOIN`；SQL 打 DEBUG 日志，测试断言日志含过滤条件 | `objectdata/ObjectQuerySqlBuilder.java:appendFilters:122` |
| R5 | 身份稳定 | `object_id = {typeCode}:{businessKey}`，不用自增/数组下标；重采不改变 ID | `objectdata/ObjectRow.java:ObjectRow.idOf:19` |
| R6 | 事务 + 幂等 + 审计 + 乐观锁 | 1~8 步在同一 JDBC 事务；`(action_code, actor, idempotency_key)` 唯一约束；`SELECT … FOR UPDATE` + `row_version` 校验；每次 Action `row_version + 1`；`act_run`/`act_edit` 记录前后值 | `action/ActionEngine.java:executeInTx:78`、`verifyVersions:233`、`objectdata/ObjectStore.java:bumpVersion:125`、`V1__init.sql:115` |
| R7 | 每个属性声明状态归属 | 元模型必须声明 `owner`；Action 改 `source/derived` 属性在**启动期**被拒绝；运行期还有一层纵深防御；采集跳过 ontology 属性 | `meta/MetamodelValidator.java:validate:53`、`MetamodelValidator.java:300`、`action/ActionEngine.java:262`、`datasource/IngestService.java:152` |
| R8 | 元模型可版本化 | 每次修改：旧 payload 进历史表、`revision + 1`、`code` 不可改；写入前先用"候选快照"整体校验 | `meta/DefinitionService.java:persist:67`、`V1__init.sql:13/26` |
| R9 | 所有 SQL 参数化 | 全部走 `NamedParameterJdbcTemplate` 具名参数；不可参数化的标识符（表名/列名）来自元模型且经白名单正则校验 | `common/SqlIdent.java:require:19`、`objectdata/ObjectStore.java:setProperty:190` |

> 两个事务管理器是刻意的：JPA 只服务元模型/数据源配置（`transactionManager`），Action 与采集用显式 JDBC 事务（`jdbcTxManager`），保证 `obj_*` 与 `act_*` 五张表共用同一个物理连接。见 `config/TransactionConfig.java:28`。

---

## 4. 状态归属（本项目最重要的设计）

每个属性必须声明 `owner`，三选一：

| owner | 含义 | 谁能改 |
|---|---|---|
| `source` | 上游拥有（源表里有这一列） | **只读**，Action 不得修改 |
| `ontology` | 上游没有，本服务是唯一真相来源 | **只有 Action 能改** |
| `derived` | 本服务计算得出 | **永远只读** |

### 4.1 示例本体的归属表

| 类型.属性 | owner | 谁写 | 为什么 |
|---|---|---|---|
| `Airport.code` / `name` / `capacity` | source | 采集 | 机场主数据在上游 |
| `Airport.weather` | derived | 采集（`src:weather_code`） | 对外呈现的气象状态由源列解码；MVP 不做气象服务集成 |
| `Flight.flightNo` / `departure` / `arrival` | source | 采集 | 航班计划来自上游 |
| `Flight.status` | **ontology** | **Action** | 上游没有"取消/关闭"这一列，状态只能由本服务产生 |
| `Flight.cancelReason` | **ontology** | **Action** | 取消处置意见，上游没有 |
| `Flight.closeReason` | **ontology** | **Action** | 关闭原因，上游没有 |
| `Flight.delayMinutes` | derived | 采集（`link:DelayedBy.minutes`） | 由 `DelayedBy` 链接指向的 `Delay.minutes` 计算 |
| `Aircraft.tailNo` / `model` / `capacity` | source | 采集 | 机队主数据在上游 |
| `Airline.code` / `name` | source | 采集 | 航司主数据在上游 |
| `Delay.id` / `minutes` / `cause` | source | 采集 | 延误事实在上游 |

**为什么 `CreateFlight` 只写 `status`？** 因为 `flightNo`/`departure`/`arrival` 是 source 属性，Action 一律不得写（R7）。新建对象只拥有"身份 + ontology 初始状态"，source 属性等上游采集回填。这是规则的直接后果，不是缺陷；README 第 10 节把它列为已知限制之一。

### 4.2 校验在哪里发生

```
保存定义时：候选快照（现有定义 + 本次修改）→ 全量语义校验 → 通过才落库
启动时    ：加载全部定义 → 再校验一次 → 有错直接让应用启动失败
运行期    ：Action 写入前再判一次 owner（纵深防御）
采集时    ：owner=ontology 的属性直接跳过
```

对应代码：`MetamodelValidator.validate`（规则）、`DefinitionService.persist`（保存前）、`MetamodelRegistry.verifyOnStartup`（启动期）、`IngestService.doIngestObjectType`（采集跳过）。

---

## 5. Action 语义

### 5.1 请求

```http
POST /api/actions/CancelFlight/execute
Content-Type: application/json
Authorization: Basic YWRtaW46YWRtaW4=

{
  "params": { "flightId": "Flight:MU5101", "reason": "目的地雷暴" },
  "expectedVersion": 0,
  "idempotencyKey": "cancel-MU5101-20261123"
}
```

批量场景用 `expectedVersions: { "Flight:CZ3101": 0, "Flight:HU7601": 0 }`。
**乐观锁是强制的**：任何已有对象的写操作都必须给出期望版本，缺失返回 400（宁可严格，也不静默跳过并发保护）。

### 5.2 时序（1~8 步同一事务）

```
1. 幂等检查     (action_code + actor + idempotency_key) 已 SUCCESS → 直接返回上次结果
1.5 占位        insert act_run(PENDING)：唯一约束把并发重复请求串行化
2. 权限检查     角色 ACL 是否允许执行该 Action
3. 参数校验     类型、必填、长度、objectRef 是否存在、目标类型是否匹配
4. 加载对象     SELECT ... WHERE object_id IN (...) ORDER BY object_id FOR UPDATE
5. 乐观锁校验   期望版本 ≠ row_version → 409 VERSION_CONFLICT
6. 前置条件     任一不满足 → 422，details.errorCode 机器可读
7. 应用变更     只允许改 owner=ontology；写 obj_prop + act_edit；row_version+1
8. 提交审计     act_run 落 SUCCESS + result
```

`create` 与"操作已有对象"不能混在同一个 Action（启动期与运行期都校验）。

### 5.3 表达式的白名单

`valueExpr` / `businessKeyExpr` / `to` 只支持三种形式，**不执行任意代码**（不做 Groovy/EL）：

| 形式 | 示例 |
|---|---|
| 字面量 | `literal:'CANCELLED'`、`literal:100`、`literal:true` |
| 参数引用 | `param:reason` |
| 当前时刻 | `now()`（仅 date/timestamp 属性） |

### 5.4 错误码

| HTTP | code | 场景 |
|---|---|---|
| 400 | `PARAM_INVALID` | 参数类型/必填/长度/objectRef 不存在、未知属性、非法算子 |
| 400 | `LIMIT_EXCEEDED` | 超过 `limits.maxObjects`（details 带 `maxObjects/requested/hint`） |
| 401 | `UNAUTHORIZED` | 未认证 |
| 403 | `FORBIDDEN` | 无类型/属性/Action 权限 |
| 404 | `OBJECT_NOT_FOUND` | 对象不存在 |
| 404 | `DEFINITION_NOT_FOUND` / `DATASET_NOT_FOUND` / `ACTION_NOT_FOUND` | 元模型/数据集/Action 定义不存在 |
| 409 | `CONFLICT` | 版本冲突（`VERSION_CONFLICT`）、幂等键冲突（`IDEMPOTENCY_IN_PROGRESS`）、唯一约束（`OBJECT_EXISTS`） |
| 422 | `PRECONDITION_FAILED` | 前置条件不满足，`details.errorCode` 为 Action 定义里声明的 `errorCode` |
| 400 | `METAMODEL_INVALID` | 元模型不合法（details.errors 列出全部问题） |
| 500 | `INTERNAL` | 未预期错误 |

### 5.5 幂等语义

- 同一 `(action, actor, idempotencyKey)` 且已 SUCCESS → 返回**上次的 runId 与结果**，`idempotentReplay=true`，不再产生副作用；
- 同键正在执行（PENDING）→ 409 `IDEMPOTENCY_IN_PROGRESS`；
- 同键曾失败 → 409（请换新键）；当前实现里同步失败会整体回滚，不会留下 FAILED 记录（见第 10 节）；
- `idempotencyKey` 可为空（不启用幂等），DDL 里 NULL 不参与唯一约束。

### 5.6 副作用（预留）

`act_run.effects jsonb` 已预留，MVP 不写入。下一期的扩展点：在事务提交后（`TransactionSynchronization.afterCommit`）读取 `effects` 投递 webhook / 写回外部系统，失败走补偿，而不是在事务内做外调。

---

## 6. 查询

### 6.1 接口

```http
POST /api/objects/query
{
  "typeCode": "Flight",
  "filters": [{"prop":"delayMinutes","op":"gte","value":20}],
  "sort": [{"prop":"delayMinutes","op":"desc"}],
  "pageSize": 2,
  "cursor": "…",
  "fields": ["flightNo","departure","arrival","status","delayMinutes"]
}
```

- 算子：`eq | in | gt | gte | lt | lte | contains | isNull`；多条件 AND；
- 返回 `items + nextCursor + hasMore`，**不返回 total**；计数走 `POST /api/objects/count`；
- 排序键：属性名，或对象级 `objectId | businessKey | createdAt | updatedAt`；MVP 只支持单字段排序；
- `fields` 与 ACL 求交集，未授权属性**在服务端被裁掉**（连 SQL 都不查）。

### 6.2 下推证据（运行日志原文）

```
对象查询 SQL [type=Flight]:
select o.object_id, o.type_code, o.business_key, o.row_version, o.state_owner, o.title, s.v_num as sort_value
from obj_object o
left join obj_prop s on s.object_id = o.object_id and s.prop_key = :sortKey and s.ord = 0
where o.type_code = :type
  and exists (select 1 from obj_prop f0 where f0.object_id = o.object_id
              and f0.prop_key = :f0Key and f0.v_num >= :f0Val)
order by s.v_num desc nulls last, o.object_id asc
limit :limit
```

口径说明：**筛选 + 排序 + 分页由一条 SQL 完成**；属性投影再按本页 `object_id` 批量取一条（`where object_id in (:ids)`），避免 N+1，也不存在"先查全量再在 Java 里过滤"。

### 6.3 执行计划

`EXPLAIN` 显示走 `idx_obj_object_type`；属性条件在数据量小的时候规划器选 seq scan（22 个对象、69 行属性，正常），强制关掉 seqscan 可以确认索引可用：

```
Limit
  ->  Nested Loop Semi Join
        ->  Index Only Scan using idx_obj_object_type on obj_object o
              Index Cond: (type_code = 'Flight'::text)
        ->  Index Scan using idx_obj_prop_num on obj_prop f0
              Index Cond: ((prop_key = 'delayMinutes') AND (v_num >= 20))
```

### 6.4 keyset 分页

- 默认按 `object_id` 升序：`and o.object_id > :cursorId order by o.object_id asc limit :limit`；
- 按属性排序：`and (s.v_num < :cursorV or (s.v_num = :cursorV and o.object_id > :cursorId) or s.v_num is null)`，配合 `nulls last`，保证翻页不重不漏；
- 游标对调用方不透明（base64 JSON `{id,v}`），同时兼容契约示例里的裸 `object_id`；
- **没有 OFFSET**。

---

## 7. 权限

三层，全部服务端强制：

| 层 | 判断 | 位置 |
|---|---|---|
| 类型级 | 能否查询/遍历该类型 | `OntologyAuthorizer.requireReadType` |
| Action 级 | 能否执行该 Action | `OntologyAuthorizer.requireExecute` |
| 属性级 | 返回时裁剪 | `OntologyAuthorizer.readableProperties:107` + `ObjectQueryService.resolveFields` |

| 角色 | 可见类型 | 可执行 Action | 可见属性 |
|---|---|---|---|
| ADMIN | 全部 | 全部 | 全部 |
| EDITOR | 5 个航空类型 | 4 个 Action | 全部 |
| VIEWER | Airport / Flight / Airline | 无 | Flight 仅 `flightNo/status/delayMinutes/departure/arrival`；Airport 仅 `code/name/weather` |

配置在 `application.yml` 的 `ontology.acl`。属性级裁剪连过滤/排序也一起管：VIEWER 想按 `cancelReason` 过滤会直接 403，而不是"过滤了但看不到"。

---

## 8. 接口清单

| 方法 | 路径 | 说明 |
|---|---|---|
| POST/GET | `/api/meta/object-types` | 创建 / 列出对象类型 |
| GET/PUT | `/api/meta/object-types/{code}` | 读取（`?revision=` 查历史）/ 修改（revision+1） |
| GET | `/api/meta/object-types/{code}/history` | 历史版本列表 |
| 同上 | `/api/meta/link-types/**`、`/api/meta/action-types/**` | 链接类型 / Action 类型 |
| GET | `/api/meta/validate` | 重新校验当前元模型，返回全部错误（不改变运行中快照） |
| POST/GET | `/api/datasources`、`/api/datasets` | 数据源（密码加密）/ 数据集 |
| POST | `/api/ingest/object-types/{code}`、`/api/ingest/links`、`/api/ingest/derived`、`/api/ingest/all` | 采集 |
| GET | `/api/ingest/stats` | 对象/属性/链接规模 |
| POST | `/api/objects/query`、`/api/objects/count` | 查询 / 计数 |
| GET | `/api/objects/{objectId}` | 单对象（`?fields=`） |
| GET | `/api/objects/{objectId}/links?linkType=&direction=OUT\|IN` | 一跳遍历 |
| POST | `/api/actions/{code}/execute` | 执行 Action（唯一写入口） |
| GET | `/api/audit/runs/{runId}`、`/api/audit/objects/{objectId}/edits`、`/api/audit/actions/{code}/runs` | 审计 |

**没有** `PUT/PATCH/DELETE /api/objects/**`（验收第 1 条）。

---

## 9. 数据库

| 表 | 用途 | 关键约束 |
|---|---|---|
| `ont_definition` / `ont_definition_history` | 元模型当前版本 / 历史 | `unique(kind, code)`、`unique(kind, code, revision)` |
| `ds_source` / `ds_dataset` | 数据源 / 数据集 | 密码只存 AES-GCM 密文 |
| `obj_object` | 对象头 | `pk object_id`、`unique(type_code, business_key)`、`state_owner ∈ {source,ontology,derived}` |
| `obj_prop` | **属性唯一存储** | `pk(object_id, prop_key, ord)`；`(prop_key, v_num/v_str/v_ts)` 索引 |
| `obj_link` | 链接 | `unique(link_type, from_object_id, to_object_id)`；两端索引 |
| `act_run` | 执行记录 | `unique(action_code, actor, idempotency_key)`；`effects jsonb` 预留 |
| `act_edit` | 变更明细 | `old_value/new_value jsonb`、`edit_type` |

类型映射：`string→v_str`、`integer/long/decimal/boolean→v_num`、`date/timestamp→v_ts`；`decimal` 用 `BigDecimal`，`timestamp` 用 `Instant`（存 UTC，`timestamptz`）。

---

## 10. 测试

```bash
mvn test
```

23 个用例，覆盖契约要求的 6 类：

| 覆盖点 | 用例 | 断言重点 |
|---|---|---|
| 唯一性违背 | `ActionSemanticsTest.同类型同_businessKey_不能重复创建` | 第二次 409，`obj_object` 仍 1 行 |
| 乐观锁冲突（并发） | `ActionSemanticsTest.并发两次改同一对象_恰好一次成功一次_409` | 两线程同版本，结果恰好 `200 + 409`，版本只 +1 |
| 幂等 | `ActionSemanticsTest.同一幂等键重放只产生一次变更` | 同 runId、`idempotentReplay=true`、`act_edit` 仍 2 行 |
| 越权 | `ActionSemanticsTest.VIEWER_不能执行_Action_且属性被裁剪` | 403 + 裁剪 |
| 查询下推 | `QueryPushdownTest.属性过滤翻译成_EXISTS_子查询并出现在_SQL_日志里` | 日志含 `exists (select 1 from obj_prop` 与 `f0.prop_key` |
| 前置条件失败 | `ActionSemanticsTest.前置条件失败返回_422_且带_error_code` | 422 + `ORDER_DEPARTED` + 事务回滚（无 act_run） |

另外还有：

- `MetamodelValidationTest`（5 个纯单元用例）：R7 规则、未声明属性、ontology 带 sourcePath、derived 缺表达式；
- `MetamodelVersioningTest`（3 个）：revision 递增、旧版本可查、`code` 不可改、**启动期校验拒绝坏 Action**；
- `QueryPushdownTest`（5 个）：keyset 不重不漏、不返回 total、数值过滤 + 排序、计数、未知属性 400；
- `IngestTest`（1 个，覆盖全链路）：22 个对象、15 条链接、派生 `delayMinutes=35`、重采 `created=0` 且 ID 不变、Action 写的 `status` 不被重采覆盖。

**关于 Testcontainers**：测试默认用 Testcontainers 拉起 `postgres:16`。若本机 Testcontainers 连不上 Docker（例如 Docker Engine 29 只接受 API ≥1.40，而旧版 docker-java 仍发 1.32），基座会自动回退到 `TEST_DB_URL` 指向的**空库**：

```bash
TEST_DB_URL=jdbc:postgresql://localhost:55434/ontology_test mvn test
```

回退只影响"用哪个库"，不改变任何测试内容（Flyway 会在该库重建全部表）。实现见 `src/test/java/com/example/ontology/support/TestDatabase.java`。

---

## 11. 20 条验收清单（逐条证据）

| # | 条目 | 证据 |
|---|---|---|
| 1 | 不存在 `PUT/PATCH /api/objects/**` | `grep -rn "PutMapping\|PatchMapping\|DeleteMapping" src/main/java` 只命中 `MetaController` 的 3 个元模型 PUT；`ObjectController` 只有 GET/POST |
| 2 | 对象数据直调除 Action 引擎外为 0 处 | `grep -rn "objectRepository.save\|jdbcTemplate.update" src/main/java` → 0 处；写 `obj_*` 的唯一入口是 `ObjectStore`，其调用方只有 `ActionEngine` 与 `IngestService`（后者跳过 ontology 属性，见 `IngestService.java:152`） |
| 3 | `ObjectType` 不含 `@Entity` | `meta/model/ObjectTypeDef.java:16` 是 `record`，全文件无 JPA 注解；有 `@Entity` 的只有 `OntDefinition`、`OntDefinitionHistory`、`DsSource`、`DsDataset` |
| 4 | 属性过滤在 SQL 里可见 | 第 6.2 节日志原文：`exists (select 1 from obj_prop f0 … f0.v_num >= :f0Val)` |
| 5 | `obj_prop` 是唯一属性存储 | `V1__init.sql:72` 的 `obj_object` 只有 `title`，无任何 JSON 属性列；属性全部在 `obj_prop`（`V1__init.sql:87`） |
| 6 | 无全量 `findAll` 打内存 | 查询路径 `ObjectQueryService.query:62` 只有 `LIMIT` 下推 + `where object_id in (:ids)` 投影；仓库里唯一的 `findAll` 是元模型加载（`DefinitionService`/`MetamodelRegistry`），属定义数据不是对象数据 |
| 7 | 分页用 keyset | `ObjectQuerySqlBuilder.appendKeyset:217`；日志：`and o.object_id > :cursorId order by o.object_id asc limit :limit`，全仓库无 `OFFSET` |
| 8 | 对象 ID 稳定 | 规则 `ObjectRow.idOf:19`；`demo.sh` 第 7.1 节重采一次后打印 `重采后 objectId 不变：Flight:MU5101`；`IngestTest` 断言重采前后 `object_id` 列表完全相等 |
| 9 | `row_version` 每次 Action 后 +1 | `ObjectStore.bumpVersion:125`；`demo.sh` 输出 `targets=[{"objectId":"Flight:MU5101","version":1}]`；`ActionSemanticsTest.取消航班_版本加一_并留下审计` |
| 10 | 并发冲突返回 409 | `ActionSemanticsTest.并发两次改同一对象_恰好一次成功一次_409`（两线程同版本）；日志/响应 details：`{"code":"VERSION_CONFLICT","expectedVersion":0,"actualVersion":1}` |
| 11 | 幂等键唯一约束在 DDL | `V1__init.sql:129`：`constraint uk_act_run_idempotency unique (action_code, actor, idempotency_key)` |
| 12 | `act_edit` 记录前后值 | `demo.sh` 第 17 节输出 `status 变更（前值→后值）= {"propKey":"status","oldValue":"CANCELLED","newValue":"CLOSED"}` |
| 13 | 改 `owner=source` 的 Action 启动期被拒 | 规则 `MetamodelValidator.java:300`；启动期入口 `MetamodelRegistry.verifyOnStartup:39`；用例 `MetamodelVersioningTest.启动期校验会拒绝修改_source_属性的_Action`（断言 `OntologyException` 且运行快照未生效） |
| 14 | Action 事务覆盖 1~8 步 | `ActionEngine.executeInTx:78` 上的 `@Transactional(transactionManager = "jdbcTxManager", rollbackFor = Exception.class)`；`TransactionConfig.java:37` 定义该管理器；`ActionSemanticsTest.前置条件失败…` 断言 422 后 `act_run` 为 0 行（PENDING 占位也被回滚） |
| 15 | 前置条件失败 422 + error_code | `demo.sh` 第 13 节 `errorCode=AIRCRAFT_IN_USE`、第 16 节 `errorCode=ALREADY_CLOSED`；测试另有 `ORDER_DEPARTED` |
| 16 | `maxObjects` 超限 400 | `demo.sh` 第 16 节：4 个目标 > 3 → `LIMIT_EXCEEDED`，details 带 `maxObjects/requested/hint` |
| 17 | 属性级裁剪在服务端 | `OntologyAuthorizer.readableProperties:107` + `ObjectQueryService.resolveFields:149`；`demo.sh` 第 18 节 VIEWER 请求 `cancelReason` 后 `properties` 里没有该字段；过滤未授权属性直接 403 |
| 18 | 元模型 revision + 历史 | `demo.sh` 第 5 节：`Flight revision=2`，`GET ?revision=1` 返回不含 `delayMinutes` 的旧定义，`/history` 列出 `[1]`；`MetamodelVersioningTest` 三个用例 |
| 19 | `demo.sh` 端到端跑通 | 运行输出末尾：`通过 54 项，失败 0 项` / `全链路演示通过。`（完整输出可粘贴，见第 1 节运行方式） |
| 20 | 本表每行指向真实位置 | 上表所有 `文件:行` 均可在仓库中 `sed -n '<行>p' <文件>` 验证；行为基准为本次提交 |

---

## 12. 已知限制与下一期

**刻意的取舍（保守实现）**

1. **`CreateFlight` 只能初始化 ontology 状态**：source 属性（`flightNo/departure/arrival`）不得由 Action 写，因此新建对象是"只有身份 + status 的壳"，source 属性等上游采集回填。若要做"本体为主数据源"的新建流程，需要引入写回上游的副作用（下一期）。
2. **没有流程类 Action（如 `DepartFlight`）**：`DEPARTED` 状态在 MVP 里不可达，`CancelFlight` 的 `ORDER_DEPARTED` 前置条件由测试夹具（直接写库造 DEPARTED）覆盖，`demo.sh` 用 `AssignAircraft` 的排班冲突演示 422。
3. **失败执行不落审计**：契约要求 1~8 步同事务，回滚即无痕，所以 `act_run.status=FAILED` 目前只在预留的补偿路径使用。要记录失败尝试，需要 `REQUIRES_NEW` 的独立审计事务（下一期）。
4. **链接与采集的竞争**：`Action` 删掉的链接，下次全量采集会按上游事实重建（`on conflict do nothing` 只能防重复，不能防"复活"）。下一期给链接加归属/墓碑。
5. **元模型循环引用需两阶段引入**：`Flight.delayMinutes` 依赖 `DelayedBy`，而 `DelayedBy` 依赖 `Flight`，所以先建类型与链接、再 `PUT` 补派生属性（正好演示 revision 1→2）。要一次成型需要"多定义草稿 + 原子发布"。
6. **单字段排序**、多值属性不支持过滤/排序/前置条件、不支持跨数据源链接——都是刻意收窄，避免语义含糊。

**明确不做（按契约）**

Interface、Function、CDC/流式、media/geoshape/timeseries/struct 类型、多数据源合并、SDK 生成、前端、多步路径与图算法、副作用外调。

**下一期优先级**

1. 链接级归属（tombstone）解决采集复活；
2. 副作用外调 + `effects` 投递与补偿；
3. `REQUIRES_NEW` 失败审计；
4. 多字段排序与 `contains` 的全文检索；
5. 元模型草稿/发布（原子引入循环引用）。
