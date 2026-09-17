-- ============================================================================
-- V65: usage_event 冻结事件基础成本（issue #710 / F21-A）——base_cost_amount。
--
-- 背景：V64 冻结了「这笔 token 当时依据什么单价算」，但**单价 × 数量**只在费率
-- 平坦时成立。AWS 的 CUR 同时给出 rate 与 line-item cost，其文档明确说明原因是
-- 多档费率（免费额度 / 阶梯价）下二者不是一元映射。
--
-- 与其等到引入阶梯价时才发现历史无法重建，不如**现在就多冻一个字段**：事件时刻
-- 依据当时价目算出的基础成本。
--
-- 关键语义（与 V64 的 price_status 配套）：
--   price_status = COMPLETE    → base_cost_amount 有值（可能是 0，即"确实免费"）
--   price_status = PARTIAL     → base_cost_amount 只含**已定价维度**的部分
--   price_status = UNAVAILABLE → base_cost_amount 为 NULL（**不是 0**）
--
-- **NULL 与 0 在此处同样是两种事实**：0 表示"当时的价格就是 0"（合法，且
-- price_status=COMPLETE），NULL 表示"当时没有价格可依"。把后者写成 0 会让
-- "价格未知"在报表上读成"免费"——这正是本列要避免的。
--
-- 与后续"事后补价"的关系：补价走**追加式**（#709 的 COST 维度调整），不修改本列、
-- 也不把 price_status 从 UNAVAILABLE 改成 COMPLETE——**"事件发生时没有价格"是一个
-- 不该被抹掉的事实**。
--
-- 与 currency 绑定：币种取自 usage_event.price_currency（V64）。金额与币种必须同时
-- 解读；将来若支持金额调整，须要求币种一致，否则另建汇率调整。
--
-- 全部可空：加可空列是元数据操作、不重写表。
-- ============================================================================

ALTER TABLE usage_event
    ADD COLUMN base_cost_amount numeric(24, 10);

COMMENT ON COLUMN usage_event.base_cost_amount IS
    '事件时刻依据当时价目算出的基础成本（与 price_currency 配对）。'
    'price_status=COMPLETE 时有值（可为 0，表示确实免费）；PARTIAL 时只含已定价维度；'
    'UNAVAILABLE 时为 NULL——NULL 与 0 是两种事实，不得互相冒充。';
