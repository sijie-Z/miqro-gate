package com.miqroera.miqrokey.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Constraints and indexes")
class ConstraintAndIndexTest extends AbstractPostgresTest {

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Nested
    @DisplayName("Composite unique constraints for tenant-aware FKs")
    class TenantAwareUniqueConstraints {
        @Test
        @DisplayName("users should have uq_users_tenant_id")
        void usersShouldHaveTenantIdUnique() {
            assertThat(getConstraintNames("users", "u")).anyMatch(c -> c.equals("uq_users_tenant_id"));
        }
        @Test
        @DisplayName("projects should have uq_projects_tenant_id")
        void projectsShouldHaveTenantIdUnique() {
            assertThat(getConstraintNames("projects", "u")).anyMatch(c -> c.equals("uq_projects_tenant_id"));
        }
        @Test
        @DisplayName("teams should have uq_teams_tenant_id")
        void teamsShouldHaveTenantIdUnique() {
            assertThat(getConstraintNames("teams", "u")).anyMatch(c -> c.equals("uq_teams_tenant_id"));
        }
        @Test
        @DisplayName("virtual_keys should have uq_virtual_keys_tenant_id")
        void virtualKeysShouldHaveTenantIdUnique() {
            assertThat(getConstraintNames("virtual_keys", "u")).anyMatch(c -> c.equals("uq_virtual_keys_tenant_id"));
        }
        @Test
        @DisplayName("upstream_subscriptions should have uq_upstream_subs_tenant_id")
        void upstreamSubsShouldHaveTenantIdUnique() {
            assertThat(getConstraintNames("upstream_subscriptions", "u"))
                    .anyMatch(c -> c.equals("uq_upstream_subs_tenant_id"));
        }
        @Test
        @DisplayName("upstream_credentials should have uq_upstream_creds_tenant_id")
        void upstreamCredsShouldHaveTenantIdUnique() {
            assertThat(getConstraintNames("upstream_credentials", "u"))
                    .anyMatch(c -> c.equals("uq_upstream_creds_tenant_id"));
        }
        @Test
        @DisplayName("project_provider_grants should have uq_grants_tenant_id")
        void grantsShouldHaveTenantIdUnique() {
            assertThat(getConstraintNames("project_provider_grants", "u"))
                    .anyMatch(c -> c.equals("uq_grants_tenant_id"));
        }
    }

    @Nested
    @DisplayName("Composite foreign keys")
    class CompositeForeignKeys {
        @Test
        @DisplayName("project_memberships should have composite FK to projects")
        void projectMembershipsShouldHaveCompositeFk() {
            assertThat(getFkNames("project_memberships")).anyMatch(c -> c.contains("project_memberships_project"));
        }
        @Test
        @DisplayName("project_memberships should have composite FK to users")
        void projectMembershipsShouldHaveCompositeFkToUsers() {
            assertThat(getFkNames("project_memberships")).anyMatch(c -> c.contains("project_memberships_user"));
        }
        @Test
        @DisplayName("virtual_keys should have composite FK to users")
        void virtualKeysShouldHaveCompositeFkToUsers() {
            assertThat(getFkNames("virtual_keys")).anyMatch(c -> c.contains("virtual_keys_user"));
        }
        @Test
        @DisplayName("virtual_keys should have composite FK to grants")
        void virtualKeysShouldHaveCompositeFkToGrants() {
            assertThat(getFkNames("virtual_keys")).anyMatch(c -> c.contains("virtual_keys_grant"));
        }
        @Test
        @DisplayName("virtual_keys should have composite FK to credentials")
        void virtualKeysShouldHaveCompositeFkToCredentials() {
            assertThat(getFkNames("virtual_keys")).anyMatch(c -> c.contains("virtual_keys_credential"));
        }
    }

    @Nested
    @DisplayName("Foreign key deletion rules")
    class DeletionRules {
        @Test
        @DisplayName("users FK should be ON DELETE RESTRICT")
        void usersHasRestrictDelete() {
            assertThat(getDeleteRule("users", "tenants")).isEqualTo("r");
        }
        @Test
        @DisplayName("virtual_keys FK to users should be RESTRICT")
        void vkUserFkIsRestrict() {
            var sql = "SELECT confdeltype FROM pg_constraint WHERE conrelid = 'virtual_keys'::regclass AND contype = 'f' AND conname LIKE '%virtual_keys_user%'";
            var rows = jdbc.queryForList(sql, Map.of());
            assertThat(rows).isNotEmpty();
            for (var r : rows) {
                assertThat(r.get("confdeltype").toString()).isIn("r", "n");
            }
        }
    }

    @Nested
    @DisplayName("Trigger existence")
    class Triggers {
        @Test
        @DisplayName("virtual_keys should have tenant consistency trigger")
        void virtualKeysShouldHaveTrigger() {
            var rows = jdbc.queryForList(
                    "SELECT tgname FROM pg_trigger WHERE tgrelid = 'virtual_keys'::regclass AND tgname LIKE '%tenant_consistency%'",
                    Map.of());
            assertThat(rows).isNotEmpty();
        }
        @Test
        @DisplayName("project_provider_grants should have credential product consistency trigger")
        void grantsShouldHaveTrigger() {
            var rows = jdbc.queryForList(
                    "SELECT tgname FROM pg_trigger WHERE tgrelid = 'project_provider_grants'::regclass AND tgname LIKE '%product_consistency%'",
                    Map.of());
            assertThat(rows).isNotEmpty();
        }
    }

    // helpers
    private List<String> getConstraintNames(String table, String type) {
        return jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = :table::regclass AND contype = :type",
                Map.of("table", table, "type", type), String.class);
    }
    private List<String> getFkNames(String table) {
        return getConstraintNames(table, "f");
    }
    private String getDeleteRule(String table, String refTable) {
        var rows = jdbc.queryForList("""
                SELECT confdeltype FROM pg_constraint c
                JOIN pg_class r ON c.confrelid = r.oid
                WHERE c.conrelid = :table::regclass AND c.contype = 'f' AND r.relname = :ref
                """, Map.of("table", table, "ref", refTable));
        if (rows.isEmpty())
            return null;
        return rows.get(0).get("confdeltype").toString();
    }
}
