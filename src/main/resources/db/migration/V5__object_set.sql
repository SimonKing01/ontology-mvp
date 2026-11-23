-- =====================================================================
-- 对象集：一次查询的持久化定义
--
-- 存的不是结果，是"筛选条件"——结果会随数据变化，条件不会。
-- 这样集合可以被复用、单独授权、被别的查询当输入源引用。
-- =====================================================================

create table obj_set (
    id          varchar(64)  primary key,
    name        varchar(120) not null,
    type_code   varchar(80)  not null,
    query_json  jsonb        not null,
    owner       varchar(80)  not null,
    shared      boolean      not null default false,
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now()
);

create index idx_obj_set_owner on obj_set (owner);
create index idx_obj_set_type on obj_set (type_code, created_at desc);
