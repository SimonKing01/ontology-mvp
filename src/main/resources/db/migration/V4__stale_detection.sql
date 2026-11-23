-- =====================================================================
-- 采集批次与失效标记
--
-- 背景：采集只做 upsert，上游删掉一行，本体对象会永远留着。
-- 做法：每次采集打一个批次号，采完把"本批次没扫到"的对象标 stale。
--       不物理删除——对象上可能挂着 Action 改过的 ontology 状态。
-- =====================================================================

alter table obj_object add column last_batch bigint;
alter table obj_object add column stale boolean not null default false;

create index idx_obj_object_batch on obj_object (type_code, last_batch);
create index idx_obj_object_stale on obj_object (type_code) where stale;
