-- ============================================================================
-- V62: 用量调整台账（issue #709 / 台账 F20）——追加型修正，绝不覆盖原始事实。
--
-- 背景：对账（F19，V42）能产出"本地用量"与"官方账单"之间的差异，但没有闭环机制——
-- 确认差异后无法修正用量，而覆盖原始事实会破坏审计可信。本表补上这一环。
--
-- 分层（本迁移只新增第二层；前三层是语义约定，不是三张表）：
--   1. usage_event        原始观察事实（observed）。本迁移绝不 UPDATE/DELETE 它。
--   2. usage_adjustments  追加型修正台账（本表）。
--   3. AdjustedUsage      = usage_event + Σ调整 → 财务/报告口径（明细/汇总/计费/导出）。
--   4. Quota 判定          仍只读 usage_event（observed），不纳入调整——
--      财务更正不得追溯改写运行时控制的历史结果（否则一笔补录会让已超额的 Key
--      在今天被重新判成未超额，等于改写历史策略）。
--
-- 纠错方式：反向行（reversal_of_id 指向被撤销的那一行）。本表不设 status/VOID
-- 之类的可变状态，也不允许"反向的反向"（业务规则，见 service），目的是让本表
-- 自身也保持 append-only——否则会出现"usage_event 不可改、adjustment 却可改"
-- 的裂缝，审计模型反而崩掉。
--
-- 计量：token 增减量（可为负）与金额增减是**互斥的两种语义**，由 adjustment_type
-- 区分，不允许一笔同时改两者（否则审计时说不清"这到底是在修正用量还是修正金额"）。
-- 本期只开放 USAGE；COST 的列先建好但不开放，避免以后为纯价格差异/汇率差/折扣/
-- 阶梯价再改一次表结构。
--
-- 时间语义：不需要额外的 effective_at/recorded_at 列——
--   · 事件发生时间 = 被引用 usage_event 行的 occurred_at（不可被调整改写）
--   · 录入时间     = 本表 created_at
--   · 入账期间（posting period）属财务政策问题，本期不落列；需要时可按
--     usage_event.occurred_at 派生，后续再定"是否允许调整跨已封账期间"。
--
-- 与 usage_event 的删除语义：usage_event 可被管理员按窗口硬删除
-- （UsageDeletionService，带确认令牌 + 审计）。故本表对 usage_event 的外键取
-- ON DELETE CASCADE——底层事实被有意抹去时，对它的修正随之失去意义。
-- 代价：被级联删掉的行不单独留痕，只体现在 usage_deletions 的审计里
-- （follow-up：可在删除审计中补记级联的调整行数）。
--
-- 租户一致性：usage_event 的主键是全局唯一的 uuid，其热写入路径不适合再加一个
-- (tenant_id, id) 唯一索引来支撑复合外键，故本表用单列外键 + 服务层校验
-- usage_event 与本行的 tenant_id 一致（复用租户作用域的仓储读取，跨租户引用无法经
-- API 产生）。
-- ============================================================================

CREATE TABLE usage_adjustments (
    id                          uuid          PRIMARY KEY,
    tenant_id                   uuid          NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    usage_event_id              uuid          NOT NULL REFERENCES usage_event (id) ON DELETE CASCADE,

    -- USAGE：token 增减量有值，金额为空；COST：金额有值，token 全空。见表级 CHECK。
    adjustment_type             varchar(16)   NOT NULL DEFAULT 'USAGE'
                                CHECK (adjustment_type IN ('USAGE', 'COST')),
    input_tokens_delta          bigint,
    output_tokens_delta         bigint,
    cache_read_tokens_delta     bigint,
    cache_creation_tokens_delta bigint,
    amount_delta                numeric(24, 10),
    currency_code               varchar(8),

    reason                      text          NOT NULL,
    reason_code                 varchar(32),

    -- 溯源：对账报告可按窗口幂等覆盖替换（F19 契约：不写历史表），故此处**不建外键**，
    -- 只存 uuid 作为来源线索；报告被替换后该引用可能悬空，属预期。
    reconciliation_row_id       uuid,

    -- 反向行：指向被撤销的那一行。撤销本身也是一条追加行。
    reversal_of_id              uuid          REFERENCES usage_adjustments (id) ON DELETE RESTRICT,

    created_by                  uuid,
    created_at                  timestamptz   NOT NULL DEFAULT now(),

    -- 幂等键：管理员重复提交（网络重试、双击）不应产生两笔调整。
    idempotency_key             varchar(128),

    CONSTRAINT ck_usage_adjustments_shape CHECK (
        (adjustment_type = 'USAGE'
            AND amount_delta IS NULL
            AND currency_code IS NULL
            AND num_nonnulls(input_tokens_delta, output_tokens_delta,
                             cache_read_tokens_delta, cache_creation_tokens_delta) >= 1)
        OR (adjustment_type = 'COST'
            AND amount_delta IS NOT NULL
            AND currency_code IS NOT NULL
            AND num_nonnulls(input_tokens_delta, output_tokens_delta,
                             cache_read_tokens_delta, cache_creation_tokens_delta) = 0)
    ),
    CONSTRAINT ck_usage_adjustments_reversal_not_self
        CHECK (reversal_of_id IS NULL OR reversal_of_id <> id)
);

-- 净额计算的主连接键：按原始行取全部调整。
CREATE INDEX idx_usage_adjustments_usage_event
    ON usage_adjustments (tenant_id, usage_event_id);

-- 台账列表（按录入时间倒序）。
CREATE INDEX idx_usage_adjustments_recent
    ON usage_adjustments (tenant_id, created_at DESC);

-- 幂等目标：沿用 usage_event 的写法（可空自然键 + 部分唯一索引）。
CREATE UNIQUE INDEX uq_usage_adjustments_idempotency
    ON usage_adjustments (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

COMMENT ON TABLE usage_adjustments IS
    '用量调整台账（#709/F20）：追加型修正，绝不覆盖 usage_event 原始事实。'
    'adjustment_type=USAGE 记 token 增删（可负），=COST 预留金额维度（本期不开放）；'
    '纠错走反向行 reversal_of_id，不设可变 status；'
    '仅用于财务/报告口径（明细/汇总/计费/导出），Quota 判定不纳入。';
