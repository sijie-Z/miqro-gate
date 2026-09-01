# ADR-0011：消费者 JWT 认证（外部系统自签 Token，网关公钥验签）

- 状态：Accepted
- 日期：2026-09-01
- 关联：[ADR-0010](0010-consumer-api-key-billing.md)（消费者 API Key 通道）、[platform-middleware-roadmap.md](../platform-middleware-roadmap.md)（P0 身份地基）、阿里云 AI 网关消费者认证（JWT/API Key）、腾讯云 AI 网关认证策略（JWT 配置）

## 决策

在 ADR-0010 的系统通道上，为消费者增加**第二种凭据：JWT**（与 API Key 并存，二选一认证）：

- **算法**：仅 RS256（SHA256withRSA）。外部系统（平台）自持私钥签发，网关只配置并保存**验签公钥**（PEM SubjectPublicKeyInfo）。网关不存任何私钥/对称密钥。
- **消费者映射**：JWT 的 `sub` claim 值 = 消费者名称（`api_consumers.name` 唯一）。对标阿里默认 `uid` claim 映射消费者。
- **Claims 校验**：`exp` 必填且必须未过期；`nbf` 可选（若存在必须 ≤ 当前时间 + 60s 时钟偏移容忍）。
- **提取位置**：`Authorization: Bearer <jwt>`（不以 `mqk_api_` 前缀开头即视为 JWT，`X-API-Key` 仍只走 API Key）。
- **管理面**：`PUT /api/v1/admin/api-consumers/{id}/jwt-key`（设置/轮换公钥，返回 SHA-256 指纹）、`DELETE`（移除，JWT 立即失效）；视图返回指纹与设置时间，不回显 PEM。
- **存储**：`api_consumers` 表新增 `jwt_public_key_pem`（text，公钥非机密，明文可存）、`jwt_key_fingerprint`（SHA-256 前 8 字节 hex）、`jwt_key_set_at`（V14）。
- **会话通道不变**：门户 session + CSRF 不受影响；管理员 session 仍可访问 billing。

**为什么不引三方 JWT 库**：RS256 验签可用 JDK 原生（`Signature`/`KeyFactory`/`Base64` url 解码）直接实现，固定只接受 RS256、拒绝其他 alg，避免 jjwt/nimbus 依赖面；与本项目 JDK 原生 Ed25519 校验（G2.1）同风格。

## 原因

- Leader 蓝图：平台注册 → OAuth 确权访问网关。JWT 是 OAuth/OIDC 的标准载体，两家云厂商的消费者认证均支持 JWT（阿里：对称 HS256 JWKS / 非对称 RS256 JWKS，网关用公钥验签；腾讯：认证策略支持 JWT，含 exp/nbf 校验、Base64 编码开关）。
- 相比 API Key 的长期密钥分发，JWT 由平台侧签发的短期 token 天然可过期、可基于平台自身账号体系签发——是「用户级确权」的前置形态，平台注册细节明确后可直接叠加（如 `sub` 换成平台 user_id）。
- 公钥验签的信任模型：网关信任平台持有的私钥（一次配置公钥），后续所有请求无需共享长期密钥。

## 后果

- V14 迁移加列；`ApiConsumer` 领域模型 +3 字段；`ApiConsumerRepository.findByName`（隐式单租户，与 `findByKeyDigest` 一致）。
- `ApiKeyAuthFilter` 扩展为双凭据认证（API Key → JWT 回退），SessionFilter 豁免逻辑不变。
- 新增 `ConsumerJwtVerifier`（JDK 原生 RS256 验签 + claims 校验 + 大小上限），管理 API 校验 PEM 可解析为 RSA 公钥。
- 安全边界：token/payload 大小上限（防大 claim DoS）；`alg=none`/非 RS256 一律拒绝；禁用消费者的 JWT 一律拒绝。
- 平台接入：管理员为消费者配置公钥（平台提供 PEM）→ 平台用自己的私钥签 JWT（`sub`=消费者名，`exp` 短期）→ 调计费 API。API Key 通道不受影响。
