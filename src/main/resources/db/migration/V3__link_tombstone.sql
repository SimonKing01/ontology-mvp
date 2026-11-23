-- =====================================================================
-- 链接墓碑
--
-- 背景：采集按上游事实重建链接，会把 Action 主动删掉的链接重新插回来
--       （obj_link 只有"现在有什么"，没有"谁删过什么"）。
-- 做法：Action 删链接时留下墓碑，采集物化时跳过有墓碑的三元组；
--       Action 主动重建同一条链接时清除墓碑。
-- =====================================================================

create table obj_link_tombstone (
    link_id        varchar(160) primary key,
    link_type      varchar(80)  not null,
    from_object_id varchar(120) not null,
    to_object_id   varchar(120) not null,
    deleted_at     timestamptz  not null default now(),
    deleted_by     varchar(80)  not null,
    run_id         varchar(40)
);

create index idx_link_tomb_pair on obj_link_tombstone (link_type, from_object_id, to_object_id);
create index idx_link_tomb_from on obj_link_tombstone (from_object_id);
