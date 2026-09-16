/**
 * Audit label helpers shared by the audit page and the overview activity
 * feed: Chinese labels for target types, NOUN_VERB action codes and the
 * JSON change summaries the backend records.
 */

/** Chinese labels for the enum values the console writes; unknown codes fall back raw. */
export const TARGET_TYPE_LABELS: Record<string, string> = {
  ADMIN_API_KEY: '管理 API 密钥',
  AGENT: '代理',
  ALERT_RULE: '告警规则',
  BUDGET: '预算',
  CONFIG: '配置',
  CONSUMER: 'API 消费者',
  DELETION: '删除任务',
  GRANT: '授权',
  MCP_SERVICE: 'MCP 服务',
  MCP_TOOL: 'MCP 工具',
  MODEL_APPROVAL: '模型审批',
  MODEL_CATALOG: '模型目录',
  PRICE_SNAPSHOT: '价格快照',
  PROJECT: '项目',
  PROVIDER_PRODUCT: '供应商产品',
  QUOTA_RULE: '配额规则',
  RECONCILIATION: '对账',
  SEAT: '席位',
  SERVICE: '服务',
  SESSION: '会话',
  SKILL: '技能',
  SUBSCRIPTION: '订阅',
  TEAM: '团队',
  TENANT: '租户',
  UPSTREAM_CREDENTIAL: '上游凭证',
  USER: '用户',
  VIRTUAL_KEY: '虚拟密钥',
  WEBHOOK: 'Webhook',
};

export function targetTypeLabel(value?: string): string {
  if (!value) return '—';
  return TARGET_TYPE_LABELS[value] ?? value;
}

/** Standalone action codes that do not follow the NOUN_VERB pattern. */
const ACTION_LABELS: Record<string, string> = {
  LOGIN_SUCCESS: '登录成功',
  LOGIN_FAILED: '登录失败',
  LOGIN: '登录',
  LOGOUT: '登出',
  LOGOUT_OTHERS: '退出其他会话',
  REGISTER: '注册',
};

/** Action codes are NOUN_VERB (VIRTUAL_KEY_CREATE); both halves map to Chinese. */
const ACTION_NOUNS: Record<string, string> = {
  ADMIN_API_KEY: '管理 API 密钥',
  ADMIN_API_KEY_SCOPE: '管理 API 密钥范围',
  AGENT: '代理',
  ALERT_RULE: '告警规则',
  API_KEY: 'API 密钥',
  BUDGET: '预算',
  CONFIG: '配置',
  CONSUMER: 'API 消费者',
  CONSUMER_SCOPE: '消费者范围',
  CREDENTIAL: '凭证',
  DELETION: '删除任务',
  EXPORT_TASK: '导出任务',
  GRANT: '授权',
  MCP_RESILIENCE: 'MCP 容错',
  MCP_SERVICE: 'MCP 服务',
  MCP_TOOL: 'MCP 工具',
  MCP_TOOL_REVISION: 'MCP 工具版本',
  MODEL_APPROVAL: '模型审批',
  MODEL_CATALOG: '模型目录',
  MODEL_CATALOG_PROBE: '模型目录探测',
  MODEL_PROBE: '模型探测',
  PASSWORD: '密码',
  PASSWORD_CHANGE: '密码修改',
  PLAN: '套餐',
  PRICE: '价格',
  PRICE_SNAPSHOT: '价格快照',
  PROJECT: '项目',
  PROJECT_MEMBER: '项目成员',
  PROVIDER_PRODUCT: '供应商产品',
  QUOTA_DEFAULT_TEMPLATE: '默认配额模板',
  QUOTA_RULE: '配额规则',
  RECONCILIATION: '对账',
  RETENTION_CONFIG: '保留策略',
  SEAT: '席位',
  SERVICE: '服务',
  SERVICE_HEALTH: '服务健康状态',
  SESSION: '会话',
  SKILL: '技能',
  SKILL_REVISION: '技能版本',
  SUBSCRIPTION: '订阅',
  TEAM: '团队',
  TEAM_MEMBER: '团队成员',
  TENANT: '租户',
  TOOLS_SYNC: '工具同步',
  TOOLS_SYNC_UPSTREAM: '工具同步上游',
  UPSTREAM_CREDENTIAL: '上游凭证',
  USAGE: '用量',
  USER: '用户',
  VALIDATION: '校验',
  VIRTUAL_KEY: '虚拟密钥',
  WEBHOOK: 'Webhook',
};

const ACTION_VERBS: Record<string, string> = {
  ACTIVATE: '激活',
  ADD: '添加',
  APPROVE: '批准',
  ARCHIVE: '归档',
  ASSIGN: '分配',
  BIND: '绑定',
  CREATE: '创建',
  CREATED: '创建',
  DELETE: '删除',
  DISABLE: '禁用',
  ENABLE: '启用',
  EXPORT: '导出',
  FAILED: '失败',
  IMPORT: '导入',
  REJECT: '驳回',
  REMOVE: '移除',
  RENAME: '重命名',
  REVOKE: '吊销',
  ROLLBACK: '回滚',
  ROTATE: '轮换',
  SUCCESS: '成功',
  UPDATE: '更新',
  UPSERT: '更新',
  VALIDATE: '校验',
};

/** 中文动作名；无法识别的编码原样保留（审计口径以原始编码为准）。 */
export function actionLabel(action?: string): string {
  if (!action) return '—';
  if (ACTION_LABELS[action]) return ACTION_LABELS[action];
  const parts = action.split('_');
  for (let cut = parts.length - 1; cut >= 1; cut -= 1) {
    const tail = parts.slice(cut).join('_');
    const verb = ACTION_VERBS[tail];
    const noun = ACTION_NOUNS[parts.slice(0, cut).join('_')];
    if (verb && noun) {
      // Outcome tails read as NOUN+OUTCOME (对账失败); action verbs lead (创建项目).
      return tail === 'FAILED' || tail === 'SUCCESS' ? `${noun}${verb}` : `${verb}${noun}`;
    }
  }
  return action;
}

/** Known change-summary keys → Chinese labels for the 摘要 column. */
const SUMMARY_KEY_LABELS: Record<string, string> = {
  cache: '缓存',
  cachePolicy: '缓存策略',
  code: '编码',
  displayName: '显示名',
  format: '格式',
  from: '开始',
  keyName: '密钥',
  modelIds: '模型',
  models: '模型',
  name: '名称',
  planScope: '套餐范围',
  productId: '产品 ID',
  providerCode: '供应商代码',
  projectId: '项目 ID',
  purpose: '用途',
  reason: '原因',
  status: '状态',
  subscriptionId: '订阅 ID',
  to: '结束',
  userId: '用户 ID',
  username: '用户名',
};

/** Backend summaries are JSON strings; render `键: 值` pairs, fall back to raw text. */
export function summaryText(raw?: string): string {
  if (!raw) return '—';
  try {
    const parsed = JSON.parse(raw) as unknown;
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return Object.entries(parsed as Record<string, unknown>)
        .map(([key, value]) => {
          const text =
            value !== null && typeof value === 'object' ? JSON.stringify(value) : String(value);
          return `${SUMMARY_KEY_LABELS[key] ?? key}: ${text}`;
        })
        .join(' · ');
    }
  } catch {
    /* not JSON — keep the raw summary */
  }
  return raw;
}
