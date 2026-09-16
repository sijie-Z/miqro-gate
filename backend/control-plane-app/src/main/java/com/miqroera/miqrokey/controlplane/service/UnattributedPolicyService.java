package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.domain.service.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tenant-level unattributed-request policy (Spec v1.1 §7.3, #647).
 *
 * <p>
 * Requests that cannot be attributed (multi-bound key without a resolvable
 * context) fail closed with {@code 400 CONTEXT_REQUIRED} unless the tenant
 * configures this policy: a dedicated credential + provider product + model
 * scope that the gateway uses to route them, with usage accounted to the
 * per-tenant UNATTRIBUTED bucket project ({@code projects.system = true}). The
 * policy NEVER borrows a project's grant/credential; when the chosen credential
 * is also referenced by an active project grant the response carries a warning
 * (plan Q1 — advisory, not blocking).
 * </p>
 */
@Service
public class UnattributedPolicyService {

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService auditService;
    private final RouteRefreshPublisher routeRefreshPublisher;

    public UnattributedPolicyService(NamedParameterJdbcTemplate jdbc, AuditService auditService,
            RouteRefreshPublisher routeRefreshPublisher) {
        this.jdbc = jdbc;
        this.auditService = auditService;
        this.routeRefreshPublisher = routeRefreshPublisher;
    }

    /**
     * Bucket project code — reserved; regular project codes are upper-case slugs,
     * this one is never offered.
     */
    static final String BUCKET_CODE = "UNATTRIBUTED";

    public record PolicyView(boolean configured, UUID projectId, UUID providerProductId, String providerProductCode,
            String providerProductName, UUID credentialId, String credentialName, List<String> models,
            Instant updatedAt, String warning) {

        static PolicyView unconfigured() {
            return new PolicyView(false, null, null, null, null, null, null, List.of(), null, null);
        }
    }

    public PolicyView get(UUID tenantId) {
        return jdbc.query("""
                SELECT p.project_id, p.provider_product_id, p.credential_id, p.model_scope, p.updated_at,
                       pp.product_code, pp.display_name AS product_name, c.credential_name
                FROM unattributed_policy p
                JOIN provider_products pp ON pp.id = p.provider_product_id
                JOIN upstream_credentials c ON c.id = p.credential_id AND c.tenant_id = p.tenant_id
                WHERE p.tenant_id = :tenantId
                """, new MapSqlParameterSource("tenantId", tenantId), rs -> {
            if (!rs.next()) {
                return PolicyView.unconfigured();
            }
            UUID credentialId = (UUID) rs.getObject("credential_id");
            return new PolicyView(true, (UUID) rs.getObject("project_id"), (UUID) rs.getObject("provider_product_id"),
                    rs.getString("product_code"), rs.getString("product_name"), credentialId,
                    rs.getString("credential_name"), readModels(rs.getString("model_scope")),
                    rs.getTimestamp("updated_at").toInstant(), sharedCredentialWarning(tenantId, credentialId));
        });
    }

    public PolicyView put(UUID tenantId, UUID adminId, UUID credentialId, UUID providerProductId, List<String> models) {
        if (credentialId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "POLICY_INVALID", "未归属策略需要提供凭证。");
        }
        UUID credentialProduct = jdbc.query("""
                SELECT s.provider_product_id FROM upstream_credentials c
                JOIN upstream_subscriptions s ON s.tenant_id = c.tenant_id AND s.id = c.subscription_id
                WHERE c.id = :credentialId AND c.tenant_id = :tenantId AND c.status = 'ACTIVE'
                """, new MapSqlParameterSource("credentialId", credentialId).addValue("tenantId", tenantId),
                rs -> rs.next() ? (UUID) rs.getObject("provider_product_id") : null);
        if (credentialProduct == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CREDENTIAL_NOT_FOUND", "凭证不存在、已停用或不属于本租户（须为 ACTIVE）。");
        }
        // The product follows the credential's subscription; an explicit value
        // must agree with it (client convenience: providerProductId optional).
        if (providerProductId != null && !credentialProduct.equals(providerProductId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNAUTH_CREDENTIAL_PRODUCT_MISMATCH",
                    "凭证所属订阅的产品与所选供应商产品不一致。");
        }
        providerProductId = credentialProduct;
        List<String> normalized = normalizeAndValidateModels(providerProductId, models);
        UUID bucketProjectId = ensureBucketProject(tenantId);
        jdbc.update("""
                INSERT INTO unattributed_policy
                    (tenant_id, project_id, credential_id, provider_product_id, model_scope, updated_by, updated_at)
                VALUES (:tenantId, :projectId, :credentialId, :providerProductId,
                        CAST(:modelScope AS jsonb), :updatedBy, now())
                ON CONFLICT (tenant_id) DO UPDATE SET
                    project_id = EXCLUDED.project_id,
                    credential_id = EXCLUDED.credential_id,
                    provider_product_id = EXCLUDED.provider_product_id,
                    model_scope = EXCLUDED.model_scope,
                    updated_by = EXCLUDED.updated_by,
                    updated_at = now()
                """,
                new MapSqlParameterSource("tenantId", tenantId).addValue("projectId", bucketProjectId)
                        .addValue("credentialId", credentialId).addValue("providerProductId", providerProductId)
                        .addValue("modelScope", modelsJson(normalized)).addValue("updatedBy", adminId));
        auditService.record(tenantId, adminId, "UNATTRIBUTED_POLICY_SET", "TENANT", tenantId,
                AuditSummaries.summary("providerProductId", providerProductId.toString(), "credentialId",
                        credentialId.toString(), "models", String.valueOf(normalized.size())),
                null);
        routeRefreshPublisher.publishChanged();
        return get(tenantId);
    }

    public void delete(UUID tenantId, UUID adminId) {
        int removed = jdbc.update("DELETE FROM unattributed_policy WHERE tenant_id = :tenantId",
                new MapSqlParameterSource("tenantId", tenantId));
        if (removed > 0) {
            // The bucket project stays (historic usage rows reference it); it is
            // a system project and can never be selected for new keys.
            auditService.record(tenantId, adminId, "UNATTRIBUTED_POLICY_CLEARED", "TENANT", tenantId, "{}", null);
            routeRefreshPublisher.publishChanged();
        }
    }

    // ------------------------------------------------------------------

    private UUID ensureBucketProject(UUID tenantId) {
        UUID existing = jdbc.query("""
                SELECT id FROM projects WHERE tenant_id = :tenantId AND code = :code
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("code", BUCKET_CODE),
                rs -> rs.next() ? (UUID) rs.getObject("id") : null);
        if (existing != null) {
            return existing;
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, tenant_id, code, name, description, status, project_tag, system, version)
                VALUES (:id, :tenantId, :code, '未归属（系统）', 'CAA 未归属桶：无法判定项目的请求记账于此', 'ACTIVE',
                        NULL, TRUE, 0)
                """, new MapSqlParameterSource("id", id).addValue("tenantId", tenantId).addValue("code", BUCKET_CODE));
        return id;
    }

    private List<String> normalizeAndValidateModels(UUID providerProductId, List<String> models) {
        List<String> normalized = new ArrayList<>();
        if (models == null) {
            return normalized;
        }
        for (String model : models) {
            if (model != null && !model.isBlank() && !normalized.contains(model.trim())) {
                normalized.add(model.trim());
            }
        }
        // #498 semantics (mirrors grants): a not-yet-synced catalog skips the
        // allowlist check; with a catalog present, every model must be known.
        Integer catalogSize = jdbc.queryForObject(
                "SELECT count(*) FROM model_catalog WHERE provider_product_id = :providerProductId",
                new MapSqlParameterSource("providerProductId", providerProductId), Integer.class);
        if (catalogSize == null || catalogSize == 0) {
            return normalized;
        }
        for (String trimmed : normalized) {
            Integer known = jdbc.queryForObject("""
                    SELECT count(*) FROM model_catalog
                    WHERE provider_product_id = :providerProductId AND model_id = :modelId
                    """, new MapSqlParameterSource("providerProductId", providerProductId).addValue("modelId", trimmed),
                    Integer.class);
            if (known == null || known == 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_NOT_IN_CATALOG", "模型不在该产品的目录中：" + trimmed);
            }
        }
        return normalized;
    }

    /** Advisory only (plan Q1): shared credentials are discouraged, not blocked. */
    private String sharedCredentialWarning(UUID tenantId, UUID credentialId) {
        Integer grants = jdbc.queryForObject("""
                SELECT count(*) FROM project_provider_grants
                WHERE tenant_id = :tenantId AND upstream_credential_id = :credentialId AND status = 'ACTIVE'
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("credentialId", credentialId),
                Integer.class);
        if (grants != null && grants > 0) {
            return "该凭证同时被 " + grants + " 条项目授权引用；规范建议使用专用凭证（与项目资源物理分离）。";
        }
        return null;
    }

    private static String modelsJson(List<String> models) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < models.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(models.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }

    private static List<String> readModels(String json) {
        List<String> models = new ArrayList<>();
        if (json == null) {
            return models;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([^\"]+)\"").matcher(json);
        while (m.find()) {
            models.add(m.group(1));
        }
        return models;
    }
}
