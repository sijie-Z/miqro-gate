package com.miqroera.miqrokey.controlplane.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documents the control-plane authentication surface in the generated OpenAPI
 * (F09): the portal session cookie, the CSRF header, and the external-system
 * API-key / JWT channels (G8.1, ADR-0010/0011). Schemes are declared as
 * components only — no operation is marked {@code security: required}, because
 * role enforcement (session roles, {@code X-API-Key}/{@code mqk_api_} JWT
 * routing) is interceptor-level and login/bootstrap must stay public.
 */
@Configuration
public class OpenApiSecuritySchemes {

    @Bean
    OpenApiCustomizer securitySchemesDocs() {
        return openApi -> {
            if (openApi.getInfo() == null) {
                openApi.setInfo(new io.swagger.v3.oas.models.info.Info());
            }
            io.swagger.v3.oas.models.info.Info info = openApi.getInfo();
            info.setTitle("MiQroKey Gateway — Control Plane API");
            info.setDescription("内部凭证治理网关管理面（认证、组织、凭证、配额、MCP、告警）" + "与外部计费查询通道。业务语义事实源见 docs/api-contract.md。");
            info.setVersion("0.1.0-SNAPSHOT");
            Components components = openApi.getComponents();
            if (components == null) {
                components = new Components();
                openApi.setComponents(components);
            }
            components.addSecuritySchemes("portalSession",
                    new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.COOKIE)
                            .name("MIQROKEY_SESSION").description("门户会话 Cookie（/api/v1/me 与 /api/v1/admin）"));
            components.addSecuritySchemes("csrfToken", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                    .in(SecurityScheme.In.HEADER).name("X-CSRF-Token").description("写操作需携带会话配套的 CSRF token"));
            components.addSecuritySchemes("apiKey",
                    new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-API-Key")
                            .description("外部系统 API Key（mqk_…，仅 /api/v1/billing/**）"));
            components.addSecuritySchemes("consumerJwt", new SecurityScheme().type(SecurityScheme.Type.HTTP)
                    .scheme("bearer").description("外部系统 JWT（RS256，sub=消费者名，仅 /api/v1/billing/**）"));
        };
    }
}
