# 平台 OAuth2/OIDC client 申请单(给平台负责人)

> 发起方:MiQroGate(内部 AI 凭证网关)｜用途:员工用平台账号免密登录 MiQroGate 控制台(P0a, ADR-0017)
> 平台环境:`https://test.forge.miqroera.com`(test);契约源:`GET /api/v3/api-docs`(2026-09-08)

## 已验证事实(2026-09-08,员工号实测)
- `POST /api/portal/auth/login`(手机号+密码)可用,返回会话令牌(非 OAuth access_token);
- `GET /api/oauth2/userinfo` 仅接受 **OAuth2 access_token**(服务端为 Sa-OAuth2,错误
  `SaOAuth2AccessTokenException: 无效 access_token`),portal 会话令牌不可用于此端点;
- 门户无"应用/开发者/客户端"自注册入口 → **client 需平台侧开通**。

## 需要平台侧开通的 client(复制此表发给平台负责人)

| 项 | 值 |
|---|---|
| OAuth2 模式 | 授权码模式(authorization_code),服务端换 token |
| 平台授权端点 | `https://test.forge.miqroera.com/api/oauth2/authorize`(契约 catch-all `/oauth2/*`) |
| 平台 token 端点 | `https://test.forge.miqroera.com/api/oauth2/token`(推断同基址,请确认) |
| 用户信息端点 | `https://test.forge.miqroera.com/api/oauth2/userinfo`(已确认,返回 `sub/username/nickname/…`) |
| 回调地址(测试环境) | `http://localhost:8080/api/v1/auth/oauth/callback` |
| 回调地址(将来生产) | 部署后网关公网地址 + `/api/v1/auth/oauth/callback`(可后补) |
| 请求 scope | `openid profile`(按平台要求调整) |
| 期望回应 | `client_id` + `client_secret`,并确认:1) token/userinfo 端点 URL;2) 回调白名单已含上述两值;3) 允许的 scope |

## 收到后 MiQroGate 侧动作(≤1 小时给打通结果)
1. 配置 6 个环境变量(见 configuration-reference `MIQROKEY_PLATFORM_OIDC_*`);
2. 真实点击流验证:登录页「平台账号登录」→ 平台授权页 → 回跳 → 自动建号/绑定 → 进控制台;
3. 复核:二次登录复用账号、拒绝授权与坏 state 提示、审计 `OAUTH_LOGIN/OAUTH_PROVISION`;
4. 结论与平台侧细节回写 issue,端点配置固化(不含真实 secret)。

## 备选说明
若平台侧短期无法开通 client,集成保持"代码就绪、开关默认关"状态(MVP 已随
ADR-0017/PR #258 交付,stub IdP 集成测试 4/4);不阻塞其余 P0 项推进。
