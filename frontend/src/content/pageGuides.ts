/**
 * Page-guide content (#656) — the Tencent-console 产品指南 pattern localized
 * to MiQroKey's chain of custody: each guide narrates one complete path a
 * first-time admin (or key user) walks, step by step, with cross-page links.
 *
 * Copy rules: imperative title + one plain sentence per step; real page names
 * in 「」; the "saved = live within seconds" fact spelled out wherever a cloud
 * console would ask for a manual sync (our snapshot refresh is automatic).
 */

export interface PageGuideStep {
  title: string;
  desc: string;
  /** In-app route for the next step (rendered as a router link). */
  to?: string;
  /** Label for `to`, e.g. 前往「授权」. */
  toText?: string;
  /** External documentation link (public repo blob, develop branch). */
  docHref?: string;
  docText?: string;
}

export interface PageGuideContent {
  title: string;
  steps: PageGuideStep[];
}

const DOCS = 'https://github.com/sijie-Z/miqro-gate/blob/develop/docs';

export const PROVIDERS_GUIDE: PageGuideContent = {
  title: '接入一家新供应商',
  steps: [
    {
      title: '登记订阅',
      desc: '在「订阅」登记你购买的套餐（PAYG / 包月 / 席位）；授权时按 项目 × 产品 × 凭证 引用。',
      to: '/app/plans',
      toText: '前往「订阅」',
    },
    {
      title: '录入凭证',
      desc: '从供应商控制台获取 API Key，在「上游凭证」录入；加密存储，创建后不可查看明文。',
      to: '/app/credentials',
      toText: '前往「上游凭证」',
    },
    {
      title: '拉取模型目录',
      desc: '用本页「模型目录 → 探测模型」从官方端点拉取模型清单，也可人工录入。',
      docHref: `${DOCS}/provider-catalog.md`,
      docText: '查看接入文档',
    },
    {
      title: '授权给项目',
      desc: '在「授权」圈定模型范围后，成员即可创建虚拟密钥；保存后数秒内生效，无需同步。',
      to: '/app/grants',
      toText: '前往「授权」',
    },
  ],
};

export const CREDENTIALS_GUIDE: PageGuideContent = {
  title: '三步用起来',
  steps: [
    {
      title: '录入凭证',
      desc: '从供应商控制台获取 API Key 并录入；「测试密钥」可先不落库做指纹比对验证。',
    },
    {
      title: '被授权引用',
      desc: '在「授权」中被引用后开始服务项目；轮换后所有引用方自动使用新版本，无需逐处修改。',
      to: '/app/grants',
      toText: '前往「授权」',
    },
    {
      title: '轮换与失效处理',
      desc: '到期或疑似泄露时点「轮换」原子切换，旧版本按宽限期退役；禁用前先看清引用它的授权。',
    },
  ],
};

export const CONSUMERS_GUIDE: PageGuideContent = {
  title: '外部系统接入四步',
  steps: [
    {
      title: '新建消费者',
      desc: '为外部系统 / 平台创建机器身份；人类用户请走「用户」管理。',
    },
    {
      title: '配置凭证',
      desc: 'API Key 仅创建时展示一次；或配置 JWT——平台自持私钥签发，网关只存公钥验签，可随时轮换。',
    },
    {
      title: '划定能力作用域',
      desc: '计费查询（billing:read）与 MCP 调用（mcp:call）按需放开，默认拒绝。',
    },
    {
      title: '监控与到期',
      desc: '「调用概览」查看近 24 小时 / 7 天转发与被拒情况；启用「消费者密钥·即将到期」告警后，到期前 7 天提醒。',
      to: '/app/alert-rules',
      toText: '前往「告警规则」',
      docHref: `${DOCS}/platform-oauth-onboarding.md`,
      docText: '查看平台对接文档',
    },
  ],
};

export const KEYS_GUIDE: PageGuideContent = {
  title: '从零到调用四步',
  steps: [
    {
      title: '加入项目',
      desc: '等待管理员在「用户 → 项目成员」把你加入项目；项目是授权与用量的治理单元。',
    },
    {
      title: '创建虚拟密钥',
      desc: '按 项目 → 授权 → 用途 → 模型 级联选择，只会出现你有权限的选项。',
    },
    {
      title: '接入客户端',
      desc: '用「接入 CC Switch」一键导入，或复制片段贴进 Claude Code / Codex / 任意 OpenAI 兼容客户端。',
    },
    {
      title: '用量与安全',
      desc: '用量页实时记账；怀疑泄露先「轮换」换新 Key，确认后再「吊销」（不可逆）。',
      to: '/app/usage',
      toText: '前往「用量」',
      docHref: `${DOCS}/virtual-key-lifecycle.md`,
      docText: '查看 Key 生命周期文档',
    },
  ],
};

export const GRANTS_GUIDE: PageGuideContent = {
  title: '授权四步',
  steps: [
    {
      title: '选定 项目 × 凭证',
      desc: '产品随凭证的订阅自动派生，无需手选。',
    },
    {
      title: '圈定模型范围',
      desc: '从模型目录勾选允许的模型；范围决定成员建 Key 时可选的模型。',
    },
    {
      title: '成员建 Key',
      desc: '授权是成员创建虚拟密钥的前提；授权生效后成员即可自助建 Key。',
    },
    {
      title: '变更前看影响',
      desc: '缩减范围或停用会影响存量 Key 的可用模型；删除前需先解除引用。',
    },
  ],
};

export const PROJECTS_GUIDE: PageGuideContent = {
  title: '项目四步',
  steps: [
    {
      title: '创建项目 + 路由标签',
      desc: '路由标签必填（虚拟密钥寻址用）；没有标签的项目成员无法建 Key。',
    },
    {
      title: '添加成员',
      desc: '在成员抽屉添加；成员身份是建 Key 的前提。',
    },
    {
      title: '建立授权',
      desc: '在「授权」按 项目 × 凭证 圈定模型范围。',
      to: '/app/grants',
      toText: '前往「授权」',
    },
    {
      title: '成员开始使用',
      desc: '成员在「我的密钥」创建虚拟密钥并接入客户端。',
      to: '/app/keys',
      toText: '前往「我的密钥」',
    },
  ],
};

export const PLAZA_GUIDE: PageGuideContent = {
  title: '找到你能用的模型',
  steps: [
    {
      title: '看可用模型',
      desc: '这里汇总你名下「可用」密钥能调用的全部模型，含输入/输出单价（与成本报表同价）。',
    },
    {
      title: '不写代码试一发',
      desc: '在「试调台」粘贴你的 Virtual Key，选模型直接发起真实调用。',
      to: '/app/playground',
      toText: '前往「试调台」',
    },
    {
      title: '缺模型就申请',
      desc: '「可申请模型」里的模型可一键提交申请；管理员审批通过后数秒内生效。',
      to: '/app/model-approvals',
      toText: '前往「模型申请」',
    },
    {
      title: '接进你的工具',
      desc: '把密钥与模型 ID 填进 Claude Code / Codex 等客户端即可；用量、审计、配额与试调台完全一致。',
    },
  ],
};

export const PLAYGROUND_GUIDE: PageGuideContent = {
  title: '三步试调一次模型',
  steps: [
    {
      title: '粘贴密钥',
      desc: '填入「我的密钥」创建时展示的 Virtual Key；它只在这个页面内使用，不会被保存。',
      to: '/app/keys',
      toText: '前往「我的密钥」',
    },
    {
      title: '读取可用模型',
      desc: '点「读取可用模型」，网关返回这把 Key 真正能调用的模型清单。',
    },
    {
      title: '发送',
      desc: '选模型、写一句话、点发送——调用与正常流量同规计费、审计并计入配额。',
    },
  ],
};
