-- ============================================================================
-- V72: CAA 归属裁定带候选基数（#1139）——usage_event 增记解析当时的
--      "该 key 的 ACTIVE 绑定数"，把 RESOLVED_SUFFIX 混着的两种情形分开：
--        1  = 后缀只是恰好命中（没有候选可挑，与 SOLE_BINDING 描述同一事实）；
--        >1 = 后缀在多个候选之间真的做出了选择。
--
-- 全列可空、纯增量；V72 之前的行保持 NULL（= 未知，不猜）。只记计数——
-- 不外露绑定明细（id 列表/凭证/产品范围），这是 #1139 的"刻意不做"。
-- 候选基数是服务端事实（来自 RouteSnapshot），与客户端声明无关。
-- 表形态：usage_event 是普通表（V6；分区的是 V8 的 request_usage_records），
-- 单条 ADD COLUMN 即完成，无分区级联问题。
-- ============================================================================

ALTER TABLE usage_event
    ADD COLUMN resolution_candidates smallint;

COMMENT ON COLUMN usage_event.resolution_candidates IS
    '解析时该 key 的 ACTIVE 绑定数（候选基数）：1=无候选可挑（后缀恰好命中 / 唯一绑定兜底），>1=真选择；V72 之前的行 NULL=未知。只记计数，不含任何绑定明细。';
