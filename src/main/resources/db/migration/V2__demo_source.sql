-- =====================================================================
-- 演示用"上游系统"：航空域模拟源库（schema: airline_domain）
-- 这些表代表本服务之外的真实系统。本体服务只读它们，不回写。
-- 注意：flight 表里没有 status / cancel_reason 列 —— 这些状态由本体拥有。
-- =====================================================================

create schema if not exists airline_domain;

create table airline_domain.airline (
    code    varchar(10) primary key,
    name    varchar(120) not null,
    country varchar(60)
);

create table airline_domain.aircraft (
    tail_no  varchar(20) primary key,
    model    varchar(40),
    capacity int
);

create table airline_domain.airport (
    code         varchar(10) primary key,
    name         varchar(120),
    capacity     bigint,
    weather_code varchar(8)
);

create table airline_domain.delay (
    id      varchar(20) primary key,
    minutes int,
    cause   varchar(40)
);

create table airline_domain.flight (
    flight_no    varchar(20) primary key,
    departure    varchar(10),
    arrival      varchar(10),
    carrier_code varchar(10),
    tail_no      varchar(20),
    delay_id     varchar(20)
);

insert into airline_domain.airline (code, name, country) values
    ('CA', '中国国航', 'CN'),
    ('MU', '中国东方航空', 'CN'),
    ('CZ', '中国南方航空', 'CN'),
    ('HU', '海南航空', 'CN');

insert into airline_domain.aircraft (tail_no, model, capacity) values
    ('B-1234', 'A320-200', 158),
    ('B-2345', 'B737-800', 168),
    ('B-3456', 'A330-300', 290),
    ('B-4567', 'B787-9', 296),
    -- 未被任何航班使用的空闲飞机：用于演示"指派飞机成功"与"占用冲突"的对比
    ('B-5678', 'A350-900', 312);

insert into airline_domain.airport (code, name, capacity, weather_code) values
    ('PEK', '北京首都国际机场', 76000000, 'CLR'),
    ('PVG', '上海浦东国际机场', 76000000, 'RA'),
    ('CAN', '广州白云国际机场', 73000000, 'CLS'),
    ('CTU', '成都天府国际机场', 56000000, 'CLR');

insert into airline_domain.delay (id, minutes, cause) values
    ('D001', 35, 'WEATHER'),
    ('D002', 20, 'ATC'),
    ('D003', 60, 'MECHANICAL');

insert into airline_domain.flight (flight_no, departure, arrival, carrier_code, tail_no, delay_id) values
    ('MU5101', 'PEK', 'PVG', 'MU', 'B-1234', 'D001'),
    ('CA1501', 'PEK', 'CAN', 'CA', 'B-2345', 'D002'),
    ('CZ3101', 'CAN', 'PEK', 'CZ', 'B-3456', null),
    ('HU7601', 'CTU', 'PEK', 'HU', 'B-4567', 'D003'),
    ('MU5102', 'PVG', 'PEK', 'MU', 'B-1234', null),
    ('CA1502', 'CAN', 'PVG', 'CA', 'B-2345', null);
