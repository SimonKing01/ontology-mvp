-- =====================================================================
-- 本体服务 MVP 初始化脚本
-- 说明：
--   1. 时间列统一用 timestamptz（存储 UTC），展示时区由应用层配置；
--   2. JSON 列统一用 jsonb；
--   3. obj_object / obj_prop / obj_link 是"对象层"，只存 source 与 ontology 属性，
--      查询直接下推到这三张表构成的投影，不复制成业务方表。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 元模型：定义本体（含 revision 历史）
-- ---------------------------------------------------------------------
create table ont_definition (
    id         bigserial primary key,
    kind       varchar(32) not null,
    code       varchar(80) not null,
    revision   int         not null,
    payload    jsonb       not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uk_ont_definition unique (kind, code),
    constraint ck_ont_definition_revision check (revision >= 1)
);
create index idx_ont_definition_kind on ont_definition (kind);

create table ont_definition_history (
    id         bigserial primary key,
    kind       varchar(32) not null,
    code       varchar(80) not null,
    revision   int         not null,
    payload    jsonb       not null,
    created_at timestamptz not null default now(),
    constraint uk_ont_definition_history unique (kind, code, revision)
);
create index idx_ont_definition_history_code on ont_definition_history (kind, code, revision desc);

-- ---------------------------------------------------------------------
-- 数据源与数据集（对象类型映射到 dataset，而不是映射到实体类）
-- ---------------------------------------------------------------------
create table ds_source (
    id              bigserial primary key,
    name            varchar(80)  not null unique,
    kind            varchar(16)  not null,
    host            varchar(255),
    port            int,
    database_name   varchar(80),
    schema_name     varchar(80),
    username        varchar(80),
    password_cipher varchar(512),
    created_at      timestamptz  not null default now(),
    updated_at      timestamptz  not null default now(),
    constraint ck_ds_source_kind check (kind in ('POSTGRES', 'CSV'))
);

create table ds_dataset (
    id           varchar(80)  primary key,
    name         varchar(120) not null,
    source_id    bigint       not null references ds_source (id),
    schema_name  varchar(80),
    table_name   varchar(120) not null,
    key_fields   varchar(255),
    columns_json jsonb,
    max_rows     int          not null default 10000,
    created_at   timestamptz  not null default now(),
    updated_at   timestamptz  not null default now()
);
create index idx_ds_dataset_source on ds_dataset (source_id);

-- ---------------------------------------------------------------------
-- 对象层
-- ---------------------------------------------------------------------
create table obj_object (
    object_id    varchar(120) primary key,
    type_code    varchar(80)  not null,
    business_key varchar(80)  not null,
    row_version  bigint       not null default 0,
    state_owner  varchar(16)  not null,
    title        varchar(512),
    created_at   timestamptz  not null default now(),
    updated_at   timestamptz  not null default now(),
    constraint uk_obj_object_business_key unique (type_code, business_key),
    constraint ck_obj_object_state_owner check (state_owner in ('source', 'ontology', 'derived'))
);
create index idx_obj_object_type on obj_object (type_code, object_id);

-- 属性投影表：属性的唯一存储，禁止把属性序列化成大 JSON 塞进 obj_object
create table obj_prop (
    object_id varchar(120)  not null,
    prop_key  varchar(80)   not null,
    ord       int           not null default 0,
    v_num     numeric(38,6),
    v_str     varchar(512),
    v_ts      timestamptz,
    primary key (object_id, prop_key, ord)
);
create index idx_obj_prop_num on obj_prop (prop_key, v_num);
create index idx_obj_prop_str on obj_prop (prop_key, v_str);
create index idx_obj_prop_ts on obj_prop (prop_key, v_ts);

create table obj_link (
    link_id        varchar(160) primary key,
    link_type      varchar(80)  not null,
    from_object_id varchar(120) not null,
    to_object_id   varchar(120) not null,
    properties     jsonb,
    created_at     timestamptz  not null default now(),
    constraint uk_obj_link unique (link_type, from_object_id, to_object_id)
);
create index idx_obj_link_from on obj_link (from_object_id);
create index idx_obj_link_to on obj_link (to_object_id);

-- ---------------------------------------------------------------------
-- Action 审计
-- ---------------------------------------------------------------------
create table act_run (
    run_id          varchar(64) primary key,
    action_code     varchar(80) not null,
    actor           varchar(80) not null,
    idempotency_key varchar(120),
    params          jsonb,
    result          jsonb,
    -- 预留：副作用外调（webhook / 写回外部系统）的记录位，MVP 不写入
    effects         jsonb,
    status          varchar(16) not null,
    error_code      varchar(64),
    error_message   text,
    started_at      timestamptz not null default now(),
    finished_at     timestamptz,
    constraint uk_act_run_idempotency unique (action_code, actor, idempotency_key),
    constraint ck_act_run_status check (status in ('PENDING', 'SUCCESS', 'FAILED'))
);
create index idx_act_run_action on act_run (action_code, started_at desc);
create index idx_act_run_actor on act_run (actor, started_at desc);

create table act_edit (
    id         bigserial primary key,
    run_id     varchar(64)  not null references act_run (run_id),
    object_id  varchar(120) not null,
    prop_key   varchar(80)  not null,
    old_value  jsonb,
    new_value  jsonb,
    edit_type  varchar(16)  not null,
    created_at timestamptz  not null default now(),
    constraint ck_act_edit_type check (edit_type in ('CREATE', 'UPDATE', 'DELETE', 'LINK_CREATE', 'LINK_DELETE'))
);
create index idx_act_edit_run on act_edit (run_id);
create index idx_act_edit_object on act_edit (object_id, created_at desc);
