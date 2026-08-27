# 前端视觉设计规范
## 视觉方向（2026-08-27，v2）

参考腾讯云 TokenHub 控制台（https://console.cloud.tencent.com/tokenhub）的浅色密集操作台：

- **卡片**：白底 + 1px 边框 + 发丝阴影（`--miqrokey-shadow-card: 0 1px 2px`）；卡片头部带 3px 主色左侧竖条。
- **品牌标识层（§4.1）**：系统中**唯一允许渐变**的两处 —— `.mk-brand-chip`（供应商/产品渐变图标徽章，8 家供应商各有专属双色渐变）与 `.mk-donut`（成本环形图）。其余表面一律平面色块。
- **紫色规则**：紫色仅允许出现在 `.mk-brand-chip` 的供应商调色板（如 DeepSeek 紫蓝），组件与表面禁止。
- **密度**：正文 13px、表格 12px、行高 44px；统计卡带图标徽章 + 大数字。
- **签名元素**：额度分段条（5 小时/周/月滚动窗口）——登录页品牌板、首页额度账本、Plans 页三处出现。
- 审美审计（vitest `aesthetic.spec.ts` 与 e2e `forbidden aesthetics`）同步收紧：渐变/紫色仅限品牌标识层，卡片仅限发丝阴影，普通容器圆角 ≤8px。


> **2026-08-27 方向修订（额度账本）**：用户反馈界面过空，参考腾讯云 TokenHub 控制台。视觉方向改为浅色密集操作台——白色卡片、细边框、蓝色主色（#0066FF）、表格密度优先；签名元素为「滚动额度分段条」（5 小时/周/月三窗口），登录页品牌区与首页/Plans 页使用。无渐变（平面色块）。原 Quiet Operations Console 的布局规则（页头/筛选条/表格密度/危险区）保留。

本文补充 `ui-specification.md`：后者负责页面字段和行为，本文负责视觉语言、布局、密度和审美验收。目标不是“生成一个后台模板”，而是形成克制、可信、适合长期运维的企业控制台。

## 1. 设计结论

采用 **Quiet Operations Console（安静的运维控制台）**：

- 信息架构参考 GitHub Settings 的左侧导航、稳定页面标题和边框分区。
- 数据密度参考成熟的财务/云控制台：表格优先、数字对齐、来源和更新时间明确。
- 组件实现使用 Element Plus，但覆盖其默认主题，形成项目自己的 tokens。
- 不逐像素照抄任何产品，不复制品牌资产；借鉴经过验证的布局习惯。

GitHub Primer 强调让页面干净、平静、减少注意力摩擦，并使用用户熟悉的布局心智模型；Element Plus 也明确把一致性、反馈、效率和可控性作为设计原则。这两者适合本项目，而不是营销型 SaaS 首页。

参考资料：

- [GitHub Primer Layout](https://primer.style/product/getting-started/foundations/layout/)
- [GitHub Primer Product UI](https://primer.style/product/)
- [Element Plus Design](https://element-plus.org/en-US/guide/design)
- [Element Plus Navigation](https://element-plus.org/en-US/guide/nav)
- [Element Plus Table](https://element-plus.org/en-US/component/table)

## 2. 比较过的三个方向

### A. GitHub Settings 风格

中性灰白、少阴影、边框划分、固定左导航、表单和危险区域清楚。优点是专业、耐看、开发者熟悉；缺点是默认数据仪表能力较弱。

### B. Grafana/NOC 运维大盘风格

深色、高密度图表、状态突出。适合监控墙，但本系统大量工作是授权、Key、表格、表单和对账；全站采用会显得压迫，也容易把管理门户误做成实时监控产品。

### C. 财务运营后台风格

白底、精确数字、筛选和表格强、状态颜色克制。适合 usage/账单对账，但如果全站过度卡片化，会出现模板化 SaaS 味道。

最终选择 A 的整体骨架 + C 的数据表达。运维监控页可以局部使用 B 的小型趋势图，但不采用全站深色大盘。

## 3. 明确禁止的“AI 味”

- 禁止紫色、蓝紫或彩虹渐变背景。
- 禁止大面积毛玻璃、发光边缘、霓虹、mesh gradient。
- 禁止所有容器都做悬浮圆角卡片。
- 禁止 16–24px 夸张圆角；普通控件半径不超过 6px。
- 禁止把每个状态都做成巨大胶囊；pill 只用于短状态标签。
- 禁止空洞欢迎语、AI sparkle 图标、装饰性机器人插图。
- 禁止首页四个巨大 KPI 卡 + 无意义折线图的模板布局。
- 禁止超大标题、过宽留白和低信息密度。
- 禁止为了“高级感”降低文字对比度或隐藏表格边界。
- 禁止默认动画、数字跳动和 hover 位移；过渡只用于状态反馈。

## 4. 视觉 Tokens

首版只做浅色主题；暗色主题不是 G5 必需项。

### 颜色

```css
:root {
  --miqrokey-bg-canvas: #f6f8fa;
  --miqrokey-bg-surface: #ffffff;
  --miqrokey-bg-subtle: #f3f4f6;
  --miqrokey-border-default: #d0d7de;
  --miqrokey-border-muted: #e5e7eb;
  --miqrokey-text-primary: #1f2328;
  --miqrokey-text-secondary: #59636e;
  --miqrokey-text-disabled: #8c959f;
  --miqrokey-accent: #0969da;
  --miqrokey-accent-hover: #0757b3;
  --miqrokey-success: #1a7f37;
  --miqrokey-warning: #9a6700;
  --miqrokey-danger: #cf222e;
  --miqrokey-info: #0969da;
}
```

状态不能只靠颜色；同时使用文字和图标。图表序列避免彩虹色，优先蓝、青、橙、灰，不使用紫色作为品牌主色。

### 字体

```css
font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC",
  "Microsoft YaHei", Arial, sans-serif;
```

Key、request ID、token 数字和代码使用系统等宽字体栈。正文 14px/20px；表格 13px/18px；页面标题 20px/28px、600 weight；不使用 32px 以上营销标题。数字列使用 `font-variant-numeric: tabular-nums`。

### 空间和形状

- 4px 基础网格；常用间距 8/12/16/24/32px。
- 控件高度 32px，重要主按钮可 36px。
- 表格普通行 40px，紧凑明细行 36px。
- 按钮/输入框 radius 6px；面板 6px；Modal 8px。
- 页面分区主要靠 1px 边框和间距，不靠阴影。
- 阴影只用于 dropdown、popover、modal；卡片默认无阴影。

## 5. 桌面布局

```text
┌──────────────────────────────────────────────────────────────────────┐
│ MiQroKey                    provider health · help · user menu     │ 56
├───────────────┬──────────────────────────────────────────────────────┤
│ Overview      │ Virtual Keys                         [Create key]    │
│ Users         │ Manage credentials used through CC Switch           │
│ Teams         ├──────────────────────────────────────────────────────┤
│ Projects      │ Filters: [Project] [Provider] [Status]   [Search]   │
│ Providers     ├──────────────────────────────────────────────────────┤
│ Plans         │ Name      Project   Provider  Models  Status  Used  │
│ Credentials   │ claude…   Core      GLM Plan  2       Active  2m   │
│ Usage         │ codex…    Tools     Qwen      1       Active  8m   │
│ Reconcile     │ ...                                                  │
│ Audit         ├──────────────────────────────────────────────────────┤
│ Settings      │ 1–25 of 42                              ‹ 1 2 ›     │
├───────────────┴──────────────────────────────────────────────────────┤
│ version · catalog version · last sync                               │
└──────────────────────────────────────────────────────────────────────┘
```

- 顶栏高 56px，不固定悬浮，不放大搜索框和装饰元素。
- 左导航宽 224px，一级分组最多两层；选中项用浅灰/浅蓝背景和左侧 2px accent。
- 主内容最大宽 1440px；数据列表可使用全宽，表单正文限制在 760px。
- 页面标题、说明、主动作在同一 header region；危险动作不与主动作并排伪装。
- 1280px 以上保持双栏；768–1279px 收窄导航；小于 768px 折叠为 drawer，功能不删减。

## 6. 页面构图

### Overview

顶部不是四张巨大卡片，而是一条紧凑状态带：供应商健康、余额告警、usage 解析失败、Webhook 失败。下方两栏：最近异常事件 + 7/30 日用量趋势。趋势图必须回答明确问题，否则换成表格。

### Virtual Keys

以表格为主。名称和掩码在第一列，固定绑定拆成项目、产品、用途、模型列。状态使用小型 label。行操作默认只显示“查看”和 kebab menu，吊销放在 menu 的危险分组。

创建 Key 使用单页表单而非五步“炫技向导”；字段有依赖时逐步展开。成功后的 Secret 使用专用一次性 Modal：白底、深色等宽 Key 区域、复制按钮、明确不可恢复警告，无彩纸动画。

### Provider / Plan

供应商 logo 只作为 20px 辅助识别，不做彩色大卡片墙。产品列表必须直接显示协议、Plan 形态、验证状态、最近同步、Base URL host。团队 Plan 详情根据共享池/席位/成员 Key 呈现真实结构。

### Usage / Reconciliation

页面采用“筛选条 → 汇总行 → 明细表”。金额右对齐，币种不省略；本地值和官方值并排显示，差异使用带正负号的 tabular number。只有数据来源明确时才显示图表。

### Settings / Dangerous Zone

普通设置用分段表单；危险区域放在页面底部，用红色标题或边框但不整块鲜红。确认文本必须包含资源名称和影响范围。

## 7. Element Plus 使用约束

- 通过 CSS variables 统一覆盖主题，不在各页面散落颜色和 radius。
- `el-table` 默认不使用厚重全边框；用横向分隔和 sticky header，数字列右对齐。
- Tag 只表示状态/协议/Plan 类型，长度短，避免每个单元格都是 Tag。
- Dialog 只用于短决策和 Secret；长表单用独立 route 或 drawer。
- Message/Notification 不承载唯一错误信息；表单错误保持在字段附近。
- Dropdown 中危险动作以 divider 分组并使用 danger color。
- 图标统一使用 Element Plus Icons 或一套许可明确的线性图标，不混用 emoji。

## 8. 文案语气

使用明确业务名词：`Virtual Key`、`上游凭证`、`供应商产品`、`团队 Plan`、`官方用量`、`本地估算`。不要用“开启你的 AI 之旅”“智能赋能”“无限可能”等营销文案。

按钮写具体动作：`创建 Virtual Key`、`验证新凭证`、`导出原始记录`、`吊销 Key`，不用模糊的 `确认`、`立即体验`。错误说明发生了什么、是否已产生上游请求、用户能做什么，并显示 request ID。

## 9. 视觉验收

- 在 1440×900、1280×800、768×1024、390×844 四个 viewport 生成 Playwright screenshot。
- 为登录、普通用户 Key 列表/创建成功、管理员 Provider、团队 Plan、Usage 对账、空/错误/加载态建立 visual baseline。
- 自动检查页面 CSS 不出现 `linear-gradient`、`radial-gradient`、大于 8px 的常规容器 radius，以及未批准的紫色 tokens。
- 页面在 100% 和 125% Windows 缩放下无关键内容截断。
- 表格使用 50、500、5000 条 Mock 数据验证密度、分页和固定列。
- 最终视觉 review 单独进行，不能只凭 E2E 功能通过视为设计完成。
