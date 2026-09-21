# ADR-0017：平台 OIDC 登录（P0a，Forge 平台身份接入）

- 日期：2026-09-08
- 状态：**Accepted**（owner「最基本能实现，大厂级别更好」方向拍板；MVP=授权码 RP 登录）
- 关联：platform-middleware-roadmap P0（F32 用户同步/F33 OAuth 的具体化第一块）、
  V31 `user_identity_link` 骨架、ADR-0010/0011（api_consumers/JWT 机器通道先例）、
  docs/open-admin-api-plan（F60 程序）。

## 事实取证（平台 OpenAPI，2026-09-08）

`https://test.forge.miqroera.com/api/v3/api-docs`（147 paths / 269 schemas）：

- `/oauth2/*`：标准 OAuth2 授权入口（通用 catch-all，response_type/client_id/redirect_uri/scope）；
- `GET /oauth2/userinfo`（OIDC UserInfo）→ `OidcUserVO{sub,username,nickname,picture,
  consumerId,consumerGroupId,configVersion,encryptedApiKey,aiGatewayStatus}`——平台已为每用户
  建模「AI 网关消费者/加密 API Key」概念；
- `/portal/auth/{login,register,sms-login,…}`：门户自建账号体系（手机号+密码/短信）；
- LoginVO：`tokenName/tokenValue/userId/phone/…`（sa-token 风格会话令牌）。

## 决策

1. **MVP = 授权码 RP 登录（大厂标准做法，对标腾讯 TSE OAuth2/OIDC、阿里 Higress JWT/OIDC）**：
   登录页出现「平台账号登录」→ 跳平台 `/oauth2/authorize` → 回调换 code→token→`/oauth2/userinfo`
   → 以 `sub` 为稳定平台身份在 `user_identity_link`（V31，idp=forge）查/建绑定 → 建立本系统
   普通门户会话。随机口令落库使该账号不可口令登录（只能走平台）。
2. **自动建号可配置**：`miqrokey.platform-oidc-auto-provision`（默认 true）首登建 USER；关闭时
   未绑定账号拒绝（ACCOUNT_UNLINKED）。
3. **安全**：state 短期 HttpOnly Cookie 防登录 CSRF；token 换发在服务端带 client_secret；
   userinfo 以 Bearer 调；审计 `OAUTH_LOGIN/OAUTH_PROVISION`；`sub` 永不外泄（仅审计摘要）。
4. **大厂级加分（后续）**：id_token JWT/JWKS 验签、PKCE、consumer/加密 API Key 通道直连
   （OidcUserVO 已含 consumerId 等——可评估平台代管消费者打通，作为 F60 消费者通道演进）。

## 配置与边界

- 端点公开：`GET /api/v1/auth/oauth/providers|start|callback`（会话存在前浏览器需要）。
- 凭据与回调需平台侧注册 client；测试环境配置后即可 E2E（当前 WAITING_FOR_CREDENTIAL）。
- 不引入新依赖：服务端用 RestClient + ObjectMapper；无 spring-security-oauth2 全家桶。

## 验收

- stub IdP 集成测试：providers 广告 → start 302+state → callback 换码 → 自动建号+链接 → 会话
  可用 /me；二次登录复用账号；state 不匹配拒登无建号。
- 真实平台 E2E：平台注册 client（test 环境）后按 runbook 冒烟（WAITING_FOR_CREDENTIAL）。
