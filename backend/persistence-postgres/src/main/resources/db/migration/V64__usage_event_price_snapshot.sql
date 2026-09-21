-- ============================================================================
-- V64: usage_event 冻结价格基座（issue #710 / 台账 F21-A）——让"历史成本"可重建。
--
-- 背景：成本目前是查询时用 `token × 查询时刻的最新价目` 现算的（所有调用点都传
-- Instant.now()）。后果是**改一次价目，所有历史报表金额跟着变**——usage_event
-- 冻住了"发生了多少 token"，却没冻住"这笔 token 当时依据什么价格算"。
--
-- 本迁移加上后者：每条用量事件携带它**自己采用的价格**，而不是让读取方去猜。
--
-- 代价列（每百万 token 单价，与 price_snapshot.unit_price 同量纲）：
--   price_input / price_output / price_cache_read / price_cache_creation
-- 元数据列：
--   price_currency        币种
--   price_effective_from  所采用价格行的生效时刻（不是事件时刻）
--   price_source          该价格的来源（MANUAL | OFFICIAL | ESTIMATED）
--   price_status          COMPLETE | PARTIAL | UNAVAILABLE
--
-- price_status 的三态与"未回填"必须可区分，这是本迁移最要紧的语义：
--   NULL        = 尚未评估（存量行刚加列时的状态；新写入行在网关开始盖章前也是它）
--   COMPLETE    = 四个计价维度都能确定
--   PARTIAL     = 部分维度有价、部分没有
--   UNAVAILABLE = 已评估，但事件发生时没有价格可查
--
-- **UNAVAILABLE 与"单价 0"是完全不同的审计含义**，因此绝不允许把"查不到价格"
-- 静默写成 0：那会把"价格未知"伪装成"免费"。读取方必须显式区分（见下）。
--
-- 取值口径：价格取自 `price_snapshot` 中 `effective_from <= 本行 occurred_at`
-- 的最新一行（回填按 occurred_at，**不是按回填时刻**）。同一 effective_from 的
-- 确定性由 `PriceSnapshotRepositoryImpl` 的 `ORDER BY effective_from DESC, id DESC`
-- 保证（原来是 `effective_from DESC` 单键，同刻多行时结果不确定，会让回填不可重复）。
--
-- 全部可空：加可空列是元数据操作、不重写表，对这张永久保留的只增表代价可接受。
-- ============================================================================

ALTER TABLE usage_event
    ADD COLUMN price_input          numeric(24, 10),
    ADD COLUMN price_output         numeric(24, 10),
    ADD COLUMN price_cache_read     numeric(24, 10),
    ADD COLUMN price_cache_creation numeric(24, 10),
    ADD COLUMN price_currency       varchar(3),
    ADD COLUMN price_effective_from timestamptz,
    ADD COLUMN price_source         varchar(32),
    ADD COLUMN price_status         varchar(16);

ALTER TABLE usage_event
    ADD CONSTRAINT ck_usage_event_price_status
    CHECK (price_status IS NULL OR price_status IN ('COMPLETE', 'PARTIAL', 'UNAVAILABLE'));

ALTER TABLE usage_event
    ADD CONSTRAINT ck_usage_event_price_source
    CHECK (price_source IS NULL OR price_source IN ('MANUAL', 'OFFICIAL', 'ESTIMATED'));

-- 回填按 occurred_at 批量扫描未评估行，故只为"待评估"的行建部分索引。
CREATE INDEX idx_usage_event_price_unbackfilled
    ON usage_event (tenant_id, occurred_at)
    WHERE price_status IS NULL;

COMMENT ON COLUMN usage_event.price_status IS
    '价格基座状态：NULL=尚未评估；COMPLETE=四维价格齐全；PARTIAL=部分维度有价；'
    'UNAVAILABLE=已评估但事件发生时无可查价格。UNAVAILABLE 不得写成单价 0——'
    '"价格未知"与"免费"是不同的事实。';

COMMENT ON COLUMN usage_event.price_effective_from IS
    '所采用价格行的 effective_from（不是事件时刻）。空值=未采用任何价格行。';
