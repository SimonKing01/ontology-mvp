#!/usr/bin/env bash
# =====================================================================
# 本体（Ontology）服务 MVP —— 端到端演示
#
# 覆盖：建源 → 建数据集 → 建对象类型（含 revision）→ 采集 → 查询（下推 + keyset）
#       → 一跳遍历 → 4 个 Action → 审计 → 幂等重放 → 越权与属性裁剪
#
# 前置：另开终端执行 `mvn spring-boot:run`（首次需 `docker compose up -d`）
# 重跑：演示假定空库；如需从零复现：
#       docker compose down -v && docker compose up -d && mvn spring-boot:run
# =====================================================================
set -uo pipefail
cd "$(dirname "$0")"

BASE="${BASE:-http://localhost:8080}"
ADMIN="admin:admin"
SRC_HOST="${SRC_HOST:-127.0.0.1}"
SRC_PORT="${SRC_PORT:-55434}"
RUN_ID="$(date +%s)"
PASS=0
FAIL=0

say()  { printf '\n\033[1;36m== %s ==\033[0m\n' "$*"; }
info() { printf '   %s\n' "$*"; }
ok()   { PASS=$((PASS + 1)); printf '   \033[32m[PASS]\033[0m %s\n' "$*"; }
bad()  { FAIL=$((FAIL + 1)); printf '   \033[31m[FAIL]\033[0m %s\n' "$*"; }

# req METHOD PATH [BODY] [USER]
req() {
  local method="$1" path="$2" body="${3:-}" user="${4:-$ADMIN}" out
  if [ -n "$body" ]; then
    out=$(curl -sS -u "$user" -X "$method" -H 'Content-Type: application/json' \
               -d "$body" -w $'\n%{http_code}' "$BASE$path")
  else
    out=$(curl -sS -u "$user" -X "$method" -H 'Content-Type: application/json' \
               -w $'\n%{http_code}' "$BASE$path")
  fi
  CODE=$(printf '%s' "$out" | tail -n1)
  BODY=$(printf '%s' "$out" | sed '$d')
}

# jq 风格的取值：jget .a.b[0].c
jget() {
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$BODY" | jq -r "$1"
  else
    printf '%s' "$BODY" | python -c '
import sys, json
d = json.load(sys.stdin)
for k in [p for p in sys.argv[1].lstrip(".").replace("[", ".").replace("]", "").split(".") if p]:
    d = d[int(k)] if k.isdigit() else d[k]
print("true" if d is True else "false" if d is False else (d if not isinstance(d, (dict, list)) else json.dumps(d, ensure_ascii=False)))
' "$1"
  fi
}

# expect CODE 描述
expect() {
  if [ "$CODE" = "$1" ]; then
    ok "$2 → HTTP $CODE"
  else
    bad "$2 → 期望 $1，实际 $CODE"
    printf '%s\n' "$BODY" | head -14
  fi
}

# ---------------------------------------------------------------------
say "0. 环境检查"
if ! curl -sS -o /dev/null -u "$ADMIN" "$BASE/api/meta/object-types"; then
  printf '\033[31m应用未启动。\033[0m 请先执行：docker compose up -d && mvn spring-boot:run\n'
  exit 1
fi
info "应用已就绪：$BASE"

req GET "/api/ingest/stats"
if [ "$CODE" = "200" ] && [ "$(jget .objects)" != "0" ]; then
  printf '\033[33m检测到对象层已有 %s 个对象。\033[0m 为保证演示可复现，请重置演示库：\n' "$(jget .objects)"
  echo "  docker compose down -v && docker compose up -d && mvn spring-boot:run"
  exit 1
fi

# ---------------------------------------------------------------------
say "1. 注册上游数据源（密码 AES-GCM 加密落库，不回显）"
req POST /api/datasources "$(cat <<JSON
{"name":"airline-src","kind":"POSTGRES","host":"$SRC_HOST","port":$SRC_PORT,
 "database":"ontology","schema":"airline_domain","username":"ontology","password":"ontology"}
JSON
)"
expect 201 "注册数据源 airline-src"
SRC_ID=$(jget .id)
info "sourceId=$SRC_ID，hasPassword=$(jget .hasPassword)"

say "2. 注册数据集（对象类型映射的目标）"
for row in "ds_airport:机场:airport:code" "ds_flight:航班:flight:flight_no" \
           "ds_aircraft:飞机:aircraft:tail_no" "ds_airline:航司:airline:code" \
           "ds_delay:延误:delay:id"; do
  IFS=':' read -r did dname dtable dkey <<<"$row"
  req POST /api/datasets "$(cat <<JSON
{"id":"$did","name":"$dname","sourceId":$SRC_ID,"schema":"airline_domain","table":"$dtable","keyFields":"$dkey","maxRows":10000}
JSON
)"
  expect 201 "数据集 $did → $dtable"
done

# ---------------------------------------------------------------------
say "3. 定义对象类型（对象类型 = 数据源映射，不是实体类）"
req POST /api/meta/object-types "$(cat <<'JSON'
{"code":"Airport","name":"机场","datasetId":"ds_airport","idField":"code","titleField":"name","status":"active",
 "properties":[
   {"key":"code","label":"三字码","type":"string","owner":"source","sourcePath":"code","required":true},
   {"key":"name","label":"名称","type":"string","owner":"source","sourcePath":"name"},
   {"key":"capacity","label":"年吞吐量","type":"long","owner":"source","sourcePath":"capacity"},
   {"key":"weather","label":"气象状态","type":"string","owner":"derived","derivedExpr":"src:weather_code"}]}
JSON
)"
expect 201 "对象类型 Airport（weather 是 derived）"

# Flight 先不含跨类型派生属性（DelayedBy 链接还没建），链接建好后再补 —— 正好演示 R8
req POST /api/meta/object-types "$(cat <<'JSON'
{"code":"Flight","name":"航班","datasetId":"ds_flight","idField":"flightNo","titleField":"flightNo","status":"active",
 "properties":[
   {"key":"flightNo","label":"航班号","type":"string","owner":"source","sourcePath":"flight_no","required":true},
   {"key":"departure","label":"出发机场","type":"string","owner":"source","sourcePath":"departure"},
   {"key":"arrival","label":"到达机场","type":"string","owner":"source","sourcePath":"arrival"},
   {"key":"status","label":"状态","type":"string","owner":"ontology"},
   {"key":"cancelReason","label":"取消原因","type":"string","owner":"ontology"},
   {"key":"closeReason","label":"关闭原因","type":"string","owner":"ontology"}]}
JSON
)"
expect 201 "对象类型 Flight（status/cancelReason/closeReason 归本体所有）"

req POST /api/meta/object-types "$(cat <<'JSON'
{"code":"Aircraft","name":"飞机","datasetId":"ds_aircraft","idField":"tailNo","titleField":"model","status":"active",
 "properties":[
   {"key":"tailNo","label":"机号","type":"string","owner":"source","sourcePath":"tail_no","required":true},
   {"key":"model","label":"机型","type":"string","owner":"source","sourcePath":"model"},
   {"key":"capacity","label":"座位数","type":"integer","owner":"source","sourcePath":"capacity"}]}
JSON
)"
expect 201 "对象类型 Aircraft"

req POST /api/meta/object-types "$(cat <<'JSON'
{"code":"Airline","name":"航司","datasetId":"ds_airline","idField":"code","titleField":"name","status":"active",
 "properties":[
   {"key":"code","label":"二字码","type":"string","owner":"source","sourcePath":"code","required":true},
   {"key":"name","label":"名称","type":"string","owner":"source","sourcePath":"name"}]}
JSON
)"
expect 201 "对象类型 Airline"

req POST /api/meta/object-types "$(cat <<'JSON'
{"code":"Delay","name":"延误","datasetId":"ds_delay","idField":"id","titleField":"cause","status":"active",
 "properties":[
   {"key":"id","label":"延误ID","type":"string","owner":"source","sourcePath":"id","required":true},
   {"key":"minutes","label":"延误分钟","type":"integer","owner":"source","sourcePath":"minutes"},
   {"key":"cause","label":"原因","type":"string","owner":"source","sourcePath":"cause"}]}
JSON
)"
expect 201 "对象类型 Delay"

say "4. 定义链接类型（fromField/toField 是两侧源列，采集时 JOIN 物化）"
req POST /api/meta/link-types "$(cat <<'JSON'
{"code":"OperatedBy","name":"由航司运营","fromTypeCode":"Flight","toTypeCode":"Airline","cardinality":"N:M",
 "fromField":"carrier_code","toField":"code","forwardName":"operatedBy","reverseName":"flights"}
JSON
)"
expect 201 "链接 Flight -[OperatedBy]-> Airline (N:M)"

req POST /api/meta/link-types "$(cat <<'JSON'
{"code":"FlownBy","name":"执飞","fromTypeCode":"Flight","toTypeCode":"Aircraft","cardinality":"N:1",
 "fromField":"tail_no","toField":"tail_no","forwardName":"flownBy","reverseName":"flights"}
JSON
)"
expect 201 "链接 Flight -[FlownBy]-> Aircraft (N:1)"

req POST /api/meta/link-types "$(cat <<'JSON'
{"code":"DelayedBy","name":"延误原因","fromTypeCode":"Flight","toTypeCode":"Delay","cardinality":"1:1",
 "fromField":"delay_id","toField":"id","forwardName":"delayedBy","reverseName":"flight"}
JSON
)"
expect 201 "链接 Flight -[DelayedBy]-> Delay (1:1)"

say "5. 补上跨类型派生属性（revision 1 → 2，旧版本进历史表）"
req PUT /api/meta/object-types/Flight "$(cat <<'JSON'
{"code":"Flight","name":"航班","datasetId":"ds_flight","idField":"flightNo","titleField":"flightNo","status":"active",
 "properties":[
   {"key":"flightNo","label":"航班号","type":"string","owner":"source","sourcePath":"flight_no","required":true},
   {"key":"departure","label":"出发机场","type":"string","owner":"source","sourcePath":"departure"},
   {"key":"arrival","label":"到达机场","type":"string","owner":"source","sourcePath":"arrival"},
   {"key":"status","label":"状态","type":"string","owner":"ontology"},
   {"key":"cancelReason","label":"取消原因","type":"string","owner":"ontology"},
   {"key":"closeReason","label":"关闭原因","type":"string","owner":"ontology"},
   {"key":"delayMinutes","label":"延误分钟","type":"integer","owner":"derived","derivedExpr":"link:DelayedBy.minutes"}]}
JSON
)"
expect 200 "Flight revision=$(jget .revision)"
req GET "/api/meta/object-types/Flight?revision=1"
expect 200 "读取历史版本 revision=1（不含 delayMinutes）"
if [[ "$(jget .definition.properties)" == *delayMinutes* ]]; then
  bad "历史版本不应包含 delayMinutes"
else
  ok "历史版本确认不含 delayMinutes"
fi

# ---------------------------------------------------------------------
say "6. 定义 4 个 Action（唯一写入口）"
req POST /api/meta/action-types "$(cat <<'JSON'
{"code":"CreateFlight","name":"创建航班","targetTypeCode":"Flight",
 "params":[{"name":"flightNo","type":"string","required":true,"maxLength":20}],
 "preconditions":[],
 "mutations":[{"op":"create","businessKeyExpr":"param:flightNo","titleExpr":"param:flightNo",
               "values":{"status":"literal:'SCHEDULED'"}}],
 "limits":{"maxObjects":1}}
JSON
)"
expect 201 "Action CreateFlight（create + 初始 ontology 状态）"

req POST /api/meta/action-types "$(cat <<'JSON'
{"code":"CancelFlight","name":"取消航班","targetTypeCode":"Flight",
 "params":[{"name":"flightId","type":"objectRef","refType":"Flight","required":true},
           {"name":"reason","type":"string","required":true,"maxLength":200}],
 "preconditions":[{"type":"notEquals","prop":"status","value":"DEPARTED",
                   "errorCode":"ORDER_DEPARTED","message":"已起飞航班不能取消"}],
 "mutations":[{"op":"update","prop":"status","valueExpr":"literal:'CANCELLED'"},
              {"op":"update","prop":"cancelReason","valueExpr":"param:reason"},
              {"op":"deleteLink","linkType":"FlownBy"}],
 "limits":{"maxObjects":1}}
JSON
)"
expect 201 "Action CancelFlight（改 status + 删 FlownBy 链接）"

req POST /api/meta/action-types "$(cat <<'JSON'
{"code":"AssignAircraft","name":"指派飞机","targetTypeCode":"Flight",
 "params":[{"name":"flightId","type":"objectRef","refType":"Flight","required":true},
           {"name":"aircraft","type":"objectRef","refType":"Aircraft","required":true}],
 "preconditions":[{"type":"relatedCountLt","linkType":"FlownBy","direction":"IN",
                   "subject":"param:aircraft","value":1,
                   "errorCode":"AIRCRAFT_IN_USE","message":"该飞机已被其他航班占用"}],
 "mutations":[{"op":"deleteLink","linkType":"FlownBy"},
              {"op":"createLink","linkType":"FlownBy","to":"param:aircraft"}],
 "limits":{"maxObjects":1}}
JSON
)"
expect 201 "Action AssignAircraft（写链接 + 排班冲突前置条件）"

req POST /api/meta/action-types "$(cat <<'JSON'
{"code":"BatchCloseFlights","name":"批量关闭航班","targetTypeCode":"Flight",
 "params":[{"name":"flights","type":"objectRef","refType":"Flight","required":true,"array":true},
           {"name":"reason","type":"string","required":true,"maxLength":200}],
 "preconditions":[{"type":"notEquals","prop":"status","value":"CLOSED",
                   "errorCode":"ALREADY_CLOSED","message":"航班已关闭"}],
 "mutations":[{"op":"update","prop":"status","valueExpr":"literal:'CLOSED'"},
              {"op":"update","prop":"closeReason","valueExpr":"param:reason"}],
 "limits":{"maxObjects":3}}
JSON
)"
expect 201 "Action BatchCloseFlights（maxObjects=3）"

say "7. 全量采集：对象 → 链接 → 派生（同一事务）"
req POST /api/ingest/all
expect 200 "ingest/all"
info "对象类型报告：$(jget .objectTypes | head -c 400)..."
info "链接物化：$(jget .links)"
info "派生计算：$(jget .derived)"
req GET /api/ingest/stats
expect 200 "对象层规模 objects=$(jget .objects) properties=$(jget .properties) links=$(jget .links)"

say "7.1 再采集一次：对象 ID 稳定、不产生重复"
req GET /api/objects/Flight:MU5101
ID_BEFORE=$(jget .objectId)
req POST /api/ingest/all
expect 200 "第二次全量采集"
info "Flight 报告=$(printf '%s' "$BODY" | python -c 'import sys,json;d=json.load(sys.stdin);print([r for r in d["objectTypes"] if r["typeCode"]=="Flight"][0])')"
req GET /api/objects/Flight:MU5101
ID_AFTER=$(jget .objectId)
if [ "$ID_BEFORE" = "$ID_AFTER" ] && [ -n "$ID_BEFORE" ]; then
  ok "重采后 objectId 不变：$ID_AFTER（{typeCode}:{businessKey}）"
else
  bad "重采后 objectId 发生变化：$ID_BEFORE → $ID_AFTER"
fi
req GET /api/ingest/stats
if [ "$(jget .objects)" = "22" ]; then
  ok "对象总数仍为 22（未产生重复对象）"
else
  bad "对象总数异常：$(jget .objects)"
fi

# ---------------------------------------------------------------------
say "8. 查询：过滤下推 SQL + keyset 分页（不返回 total）"
req POST /api/objects/query "$(cat <<'JSON'
{"typeCode":"Flight",
 "filters":[{"prop":"delayMinutes","op":"gte","value":20}],
 "sort":[{"prop":"delayMinutes","op":"desc"}],
 "pageSize":2}
JSON
)"
expect 200 "查询延误≥20 的航班，按延误降序取 2 条"
info "items=$(jget .items | head -c 260)..."
info "hasMore=$(jget .hasMore) nextCursor=$(jget .nextCursor)"
CUR=$(jget .nextCursor)
req POST /api/objects/query "$(cat <<JSON
{"typeCode":"Flight","filters":[{"prop":"delayMinutes","op":"gte","value":20}],
 "sort":[{"prop":"delayMinutes","op":"desc"}],"pageSize":2,"cursor":"$CUR"}
JSON
)"
expect 200 "用 nextCursor 取第二页（keyset，无 OFFSET）"
info "第二页 ids=$(jget .items | head -c 200)..."
if [ "$(printf '%s' "$BODY" | grep -c '"total"')" = "0" ]; then
  ok "响应中没有 total（计数走独立接口）"
else
  bad "响应不应包含 total"
fi

req POST /api/objects/query "$(cat <<'JSON'
{"typeCode":"Flight",
 "filters":[{"prop":"status","op":"isNull"}],
 "pageSize":3,
 "fields":["flightNo","departure","arrival","status","delayMinutes"]}
JSON
)"
expect 200 "查询尚未产生 ontology 状态的航班（isNull 谓词）"

req POST /api/objects/count '{"typeCode":"Flight"}'
expect 200 "计数接口 total=$(jget .count)"

say "9. 单对象读取与一跳遍历"
req GET /api/objects/Flight:MU5101
expect 200 "读取 Flight:MU5101（版本 $(jget .version)）"
info "properties=$(jget .properties)"

req GET "/api/objects/Flight:MU5101/links?linkType=FlownBy"
expect 200 "一跳遍历 FlownBy"
info "links=$(jget .items)"
req GET "/api/objects/Flight:MU5101/links?linkType=DelayedBy"
expect 200 "一跳遍历 DelayedBy"
info "links=$(jget .items)"

# ---------------------------------------------------------------------
say "10. 执行 Action：取消航班（事务 + 行锁 + 乐观锁 + 审计）"
req GET /api/objects/Flight:MU5101
VER=$(jget .version)
info "取消前版本 version=$VER"
req POST /api/actions/CancelFlight/execute "$(cat <<JSON
{"params":{"flightId":"Flight:MU5101","reason":"目的地雷暴"},
 "expectedVersion":$VER,
 "idempotencyKey":"cancel-MU5101-$RUN_ID"}
JSON
)"
expect 200 "CancelFlight 成功"
RUN1=$(jget .runId)
info "runId=$RUN1，targets=$(jget .result.targets)"
info "edits=$(jget .result.edits)"

say "11. 幂等重放：同一个 idempotencyKey"
req POST /api/actions/CancelFlight/execute "$(cat <<JSON
{"params":{"flightId":"Flight:MU5101","reason":"目的地雷暴"},
 "expectedVersion":$VER,
 "idempotencyKey":"cancel-MU5101-$RUN_ID"}
JSON
)"
expect 200 "重放返回上次结果"
info "idempotentReplay=$(jget .idempotentReplay) runId=$(jget .runId)"
if [ "$(jget .runId)" = "$RUN1" ] && [ "$(jget .idempotentReplay)" = "true" ]; then
  ok "runId 一致且标记为重放，未产生第二次副作用"
else
  bad "幂等重放行为不正确"
fi

say "12. 乐观锁：拿旧版本再提交"
req POST /api/actions/CancelFlight/execute "$(cat <<JSON
{"params":{"flightId":"Flight:MU5101","reason":"再取消一次"},
 "expectedVersion":$VER,
 "idempotencyKey":"cancel-MU5101-stale-$RUN_ID"}
JSON
)"
expect 409 "版本冲突 → 409 CONFLICT"
info "details=$(jget .details)"

# ---------------------------------------------------------------------
say "13. 前置条件：飞机被占用 → 422 + errorCode"
req GET /api/objects/Flight:CA1501
CA_VER=$(jget .version)
req POST /api/actions/AssignAircraft/execute "$(cat <<JSON
{"params":{"flightId":"Flight:CA1501","aircraft":"Aircraft:B-3456"},
 "expectedVersion":$CA_VER,
 "idempotencyKey":"assign-busy-$RUN_ID"}
JSON
)"
expect 422 "指派已被 CZ3101 占用的 B-3456"
info "errorCode=$(jget .details.errorCode)"

say "14. 指派空闲飞机 → 成功并写链接"
req POST /api/actions/AssignAircraft/execute "$(cat <<JSON
{"params":{"flightId":"Flight:CA1501","aircraft":"Aircraft:B-5678"},
 "expectedVersion":$CA_VER,
 "idempotencyKey":"assign-free-$RUN_ID"}
JSON
)"
expect 200 "指派空闲飞机 B-5678"
info "edits=$(jget .result.edits)"
req GET "/api/objects/Flight:CA1501/links?linkType=FlownBy"
expect 200 "复验 FlownBy 链接"
info "links=$(jget .items)"

# ---------------------------------------------------------------------
say "15. 创建对象（ontology-owned 初始状态）"
req POST /api/actions/CreateFlight/execute "$(cat <<JSON
{"params":{"flightNo":"MU9999"},"expectedVersion":0,"idempotencyKey":"create-MU9999-$RUN_ID"}
JSON
)"
expect 200 "CreateFlight MU9999"
req GET /api/objects/Flight:MU9999
expect 200 "读回新建对象"
info "stateOwner=$(jget .stateOwner)（本体创建，不来自上游）properties=$(jget .properties)"

say "16. 批量 Action：maxObjects 上限与分片"
req POST /api/actions/BatchCloseFlights/execute "$(cat <<JSON
{"params":{"flights":["Flight:CZ3101","Flight:HU7601","Flight:CA1502","Flight:MU5102"],
           "reason":"台风停场"},
 "expectedVersion":0,"idempotencyKey":"batch-over-$RUN_ID"}
JSON
)"
expect 400 "4 个目标 > maxObjects=3 → 400 LIMIT_EXCEEDED"
info "details=$(jget .details)"

req POST /api/objects/query '{"typeCode":"Flight","pageSize":20,"fields":["flightNo","status"]}'
CZ_V=$(printf '%s' "$BODY" | python -c '
import sys,json
d=json.load(sys.stdin)
m={i["objectId"]: i["version"] for i in d["items"]}
print(json.dumps({("Flight:"+k): m["Flight:"+k] for k in ("CZ3101","HU7601")}))
')
req POST /api/actions/BatchCloseFlights/execute "$(cat <<JSON
{"params":{"flights":["Flight:CZ3101","Flight:HU7601"],"reason":"台风停场"},
 "expectedVersions":$CZ_V,"idempotencyKey":"batch-ok-$RUN_ID"}
JSON
)"
expect 200 "分片后批量关闭 2 个航班"
NEW_V=$(printf '%s' "$CZ_V" | python -c '
import sys,json
d=json.load(sys.stdin)
print(json.dumps({k: v+1 for k, v in d.items()}))
')
req POST /api/actions/BatchCloseFlights/execute "$(cat <<JSON
{"params":{"flights":["Flight:CZ3101","Flight:HU7601"],"reason":"再关一次"},
 "expectedVersions":$NEW_V,"idempotencyKey":"batch-again-$RUN_ID"}
JSON
)"
expect 422 "重复关闭 → 422 ALREADY_CLOSED"
info "errorCode=$(jget .details.errorCode)"

# ---------------------------------------------------------------------
say "17. 审计：谁、什么时候、把什么从什么改成什么"
# 再做一次状态迁移，让审计里出现"前值 → 后值"（CANCELLED → CLOSED）
req POST /api/actions/BatchCloseFlights/execute "$(cat <<JSON
{"params":{"flights":["Flight:MU5101"],"reason":"取消后归档"},
 "expectedVersions":{"Flight:MU5101":1},
 "idempotencyKey":"archive-MU5101-$RUN_ID"}
JSON
)"
expect 200 "归档已取消航班（CANCELLED → CLOSED）"

req GET "/api/audit/objects/Flight:MU5101/edits?limit=8"
expect 200 "该对象最近的变更记录"
STATUS_EDIT=$(printf '%s' "$BODY" | python -c '
import sys, json
d = json.load(sys.stdin)
e = [x for x in d if x["propKey"] == "status"][0]
print(json.dumps({"propKey": e["propKey"], "oldValue": e.get("oldValue"), "newValue": e.get("newValue")}, ensure_ascii=False))
')
info "status 变更（前值→后值）= $STATUS_EDIT"
if [[ "$STATUS_EDIT" == *CANCELLED* && "$STATUS_EDIT" == *CLOSED* ]]; then
  ok "act_edit 同时记录了前值 CANCELLED 与后值 CLOSED"
else
  bad "act_edit 前后值不完整：$STATUS_EDIT"
fi

req GET "/api/audit/runs/$RUN1"
expect 200 "按 runId 查执行记录"
info "run.status=$(jget .run.status) actor=$(jget .run.actor)"
info "edits（含前后值）=$(jget .edits)"

req GET "/api/audit/objects/Flight:MU5101/edits?limit=10"
expect 200 "按对象查变更历史"
info "count=$(printf '%s' "$BODY" | python -c 'import sys,json;print(len(json.load(sys.stdin)))')"

# ---------------------------------------------------------------------
say "18. 权限：VIEWER 不能执行 Action，未授权属性被服务端裁剪"
req POST /api/actions/CancelFlight/execute "$(cat <<JSON
{"params":{"flightId":"Flight:CA1501","reason":"越权尝试"},"expectedVersion":0,
 "idempotencyKey":"viewer-$RUN_ID"}
JSON
)" "viewer:viewer"
expect 403 "VIEWER 执行 CancelFlight"

req GET "/api/objects/Flight:CA1501?fields=flightNo,status,cancelReason,delayMinutes" "" "viewer:viewer"
expect 200 "VIEWER 请求未授权属性 cancelReason"
info "properties=$(jget .properties)"
if [[ "$(jget .properties)" == *cancelReason* ]]; then
  bad "cancelReason 应被裁剪"
else
  ok "cancelReason 已被服务端裁剪"
fi

# ---------------------------------------------------------------------
say "19. 汇总"
printf '   通过 %d 项，失败 %d 项\n' "$PASS" "$FAIL"
if [ "$FAIL" -eq 0 ]; then
  printf '\033[32m   全链路演示通过。\033[0m\n'
  echo "   控制台: $BASE/    Swagger: $BASE/swagger-ui.html"
  exit 0
else
  printf '\033[31m   存在失败项，请查看上面的输出与日志。\033[0m\n'
  exit 1
fi
