package com.miqroera.miqrokey.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.domain.cache.CacheKey;
import com.miqroera.miqrokey.domain.usage.TokenBucket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * #508 regression: the L2 write path failed with SQLSTATE 42804 (String
 * parameter into a jsonb column without an explicit cast; Spring surfaces it as
 * "bad SQL grammar"), leaving {@code cache_entry} forever empty and silently
 * degrading the cache to L1-only. This IT locks the full roundtrip against a
 * real PostgreSQL: write → read (L2_HIT) → overwrite semantics → project
 * invalidation → expiry.
 */
class PostgresCacheProviderIntegrationTest {

    static PostgreSQLContainer<?> pg;
    static DriverManagerDataSource ds;

    static UUID tenant;
    static UUID project;
    static UUID otherProject;
    static UUID vk;
    static UUID product;

    @BeforeAll
    static void start() throws Exception {
        pg = new PostgreSQLContainer<>("postgres:17-alpine");
        pg.start();
        ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        try (var c = ds.getConnection(); var st = c.createStatement()) {
            st.execute("CREATE TABLE tenants (id uuid PRIMARY KEY)");
            st.execute("CREATE TABLE provider_products (id uuid PRIMARY KEY)");
            st.execute(
                    "CREATE TABLE projects (tenant_id uuid NOT NULL, id uuid NOT NULL, PRIMARY KEY (tenant_id, id))");
            st.execute(
                    "CREATE TABLE virtual_keys (tenant_id uuid NOT NULL, id uuid NOT NULL, PRIMARY KEY (tenant_id, id))");
            st.execute(
                    """
                            CREATE TABLE cache_entry (
                                id uuid PRIMARY KEY,
                                tenant_id uuid NOT NULL REFERENCES tenants (id),
                                cache_key bytea NOT NULL,
                                virtual_key_id uuid NOT NULL,
                                project_id uuid NOT NULL,
                                provider_product_id uuid NOT NULL,
                                model_id varchar(128) NOT NULL,
                                provider_request_id varchar(128),
                                status_code integer NOT NULL,
                                content_type varchar(128),
                                response_headers jsonb NOT NULL DEFAULT '{}',
                                body bytea NOT NULL,
                                meta_json jsonb NOT NULL DEFAULT '{}',
                                hit_count_l1 bigint NOT NULL DEFAULT 0,
                                hit_count_l2 bigint NOT NULL DEFAULT 0,
                                expires_at timestamptz,
                                created_at timestamptz NOT NULL DEFAULT now(),
                                updated_at timestamptz NOT NULL DEFAULT now(),
                                CONSTRAINT fk_vk FOREIGN KEY (tenant_id, virtual_key_id) REFERENCES virtual_keys (tenant_id, id),
                                CONSTRAINT fk_pj FOREIGN KEY (tenant_id, project_id) REFERENCES projects (tenant_id, id),
                                CONSTRAINT fk_pp FOREIGN KEY (provider_product_id) REFERENCES provider_products (id)
                            )""");
            st.execute("CREATE UNIQUE INDEX uq_cache_entry_tenant_key ON cache_entry (tenant_id, cache_key)");
            tenant = UUID.randomUUID();
            project = UUID.randomUUID();
            otherProject = UUID.randomUUID();
            vk = UUID.randomUUID();
            product = UUID.randomUUID();
            st.execute("INSERT INTO tenants VALUES ('%s')".formatted(tenant));
            st.execute("INSERT INTO provider_products VALUES ('%s')".formatted(product));
            st.execute("INSERT INTO projects VALUES ('%s','%s')".formatted(tenant, project));
            st.execute("INSERT INTO projects VALUES ('%s','%s')".formatted(tenant, otherProject));
            st.execute("INSERT INTO virtual_keys VALUES ('%s','%s')".formatted(tenant, vk));
        }
    }

    @AfterAll
    static void stop() {
        if (pg != null) {
            pg.stop();
        }
    }

    private static CacheKey key(String seed) throws Exception {
        return CacheKey.from(MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8)));
    }

    private static CachedResponse response(String body) {
        return new CachedResponse(200, "application/json", Map.of("content-type", List.of("application/json")),
                body.getBytes(StandardCharsets.UTF_8), new TokenBucket(11L, 22L, null, null, 11L, 22L, 33L, null),
                true);
    }

    private static PostgresCacheProvider provider(Duration ttl) {
        return new PostgresCacheProvider(new NamedParameterJdbcTemplate(ds), new ObjectMapper(), ttl);
    }

    @Test
    void writeThenReadReturnsL2HitWithJsonbRoundTrip() throws Exception {
        var p = provider(Duration.ofMinutes(5));
        var k = key("roundtrip");
        p.put(k, tenant, vk, project, product, "deepseek-flash", response("{\"ok\":true}"));

        var lookup = p.get(tenant, k);
        assertEquals(GatewayResponseCache.LookupLevel.L2_HIT, lookup.level());
        var cached = lookup.response().orElseThrow();
        assertEquals(200, cached.statusCode());
        assertEquals("{\"ok\":true}", new String(cached.body(), StandardCharsets.UTF_8));
        assertEquals(List.of("application/json"), cached.headers().get("content-type"));
        assertEquals(33L, cached.usage().totalTokens().longValue());
    }

    @Test
    void overwriteUpdatesBodyButNotHitCounters() throws Exception {
        var p = provider(Duration.ofMinutes(5));
        var jdbc = new JdbcTemplate(ds);
        var k = key("overwrite");
        p.put(k, tenant, vk, project, product, "deepseek-flash", response("v1"));
        jdbc.update("UPDATE cache_entry SET hit_count_l1 = 7, hit_count_l2 = 3 WHERE cache_key = ?", k.sha256());
        p.put(k, tenant, vk, project, product, "deepseek-flash", response("v2"));

        assertEquals(1,
                jdbc.queryForObject("SELECT count(*) FROM cache_entry WHERE cache_key = ?", Integer.class, k.sha256())
                        .intValue());
        assertEquals("v2", new String(p.get(tenant, k).response().orElseThrow().body(), StandardCharsets.UTF_8));
        Map<String, Object> counters = jdbc
                .queryForMap("SELECT hit_count_l1, hit_count_l2 FROM cache_entry WHERE cache_key = ?", k.sha256());
        assertEquals(7L, ((Number) counters.get("hit_count_l1")).longValue());
        assertEquals(3L, ((Number) counters.get("hit_count_l2")).longValue());
    }

    @Test
    void invalidateProjectRemovesOnlyThatProject() throws Exception {
        var p = provider(Duration.ofMinutes(5));
        var a = key("inv-a");
        var b = key("inv-b");
        p.put(a, tenant, vk, project, product, "m", response("a"));
        p.put(b, tenant, vk, otherProject, product, "m", response("b"));
        p.invalidateProject(tenant, project);

        assertEquals(GatewayResponseCache.LookupLevel.MISS, p.get(tenant, a).level());
        assertEquals(GatewayResponseCache.LookupLevel.L2_HIT, p.get(tenant, b).level());
    }

    @Test
    void expiredEntryIsAMiss() throws Exception {
        var p = provider(Duration.ofSeconds(-10));
        var k = key("expired");
        p.put(k, tenant, vk, project, product, "m", response("gone"));
        assertEquals(GatewayResponseCache.LookupLevel.MISS, p.get(tenant, k).level());
    }
}
