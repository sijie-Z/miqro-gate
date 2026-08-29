# 安全策略

## 报告漏洞

MiQroGate 是凭证治理网关：请勿在公开 Issue 中披露安全漏洞（尤其涉及凭证、密钥、鉴权绕过的内容）。

请通过以下方式私密报告：

- **GitHub 安全通告（Security Advisory）**：仓库页 → Security → Report a vulnerability
- **邮件**：仓库维护者（通过 GitHub 个人主页获取联系方式）

我们会在 **7 天内**确认并回应报告，修复后通过 GitHub 安全通告（或随版本发布）披露。

## 支持范围

- 支持最新发布版本的漏洞修复。
- 安全修复会以 `security` 类型 commit 记录，并优先合入。
- 本项目为单客户私有化部署；客户侧部署请确保使用最新镜像 digest 并定期更新。

## 已知安全设计（供审查参考）

- 上游凭证 AES-256-GCM 加密存储；Virtual Key 仅存 HMAC 摘要。
- 透明代理不保存 prompt / 模型回答；正文不进日志与审计。
- SSRF 双重门控（DNS 解析后地址校验 + 公网限定）。
- 审计事件哈希链（篡改可检测）。
- 依赖扫描（Dependabot）、供应链评分（OSSF Scorecard）、SAST（CodeQL）持续运行。
