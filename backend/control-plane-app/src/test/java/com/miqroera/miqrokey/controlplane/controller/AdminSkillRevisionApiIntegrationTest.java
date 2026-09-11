package com.miqroera.miqrokey.controlplane.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Skill version management (I14, {@code skill_revisions} V49, Tencent raw 20):
 * a same-name upload publishes the next immutable revision, history is
 * metadata-only (never the package bytes), rollback activates an older revision
 * idempotently, and the active package is what downloads serve.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("Skill revision API integration tests (PostgreSQL)")
class AdminSkillRevisionApiIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    private static final String SKILL_MD = """
            ---
            name: web-scraper
            description: Scrapes public web pages into markdown.
            author: Platform Team
            license: MIT
            tags:
              - scraping
              - web
            ---

            # Web Scraper

            Scrapes a URL and returns clean markdown.
            """;

    private static final String SKILL_MD_V2 = SKILL_MD.replace("Scrapes public web pages into markdown.",
            "Scrapes pages AND sitemaps into markdown.");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        AbstractControlPlaneIntegrationTest.configureProperties(registry);
        registry.add("miqrokey.bootstrap-secret-file",
                () -> AdminProviderApiIntegrationTest.BootstrapHelper.secretFile().toAbsolutePath().toString());
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private Cookie adminSession;
    private Cookie adminCsrf;
    private String adminCsrfToken;

    @BeforeEach
    void setUp() throws Exception {
        clean();
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(
                                AdminProviderApiIntegrationTest.BootstrapHelper.secret(), "root", "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        adminSession = cookie(boot, "MIQROKEY_SESSION");
        adminCsrf = cookie(boot, "MIQROKEY_CSRF");
        adminCsrfToken = adminCsrf != null ? adminCsrf.getValue() : "";
        Map<?, ?> body = objectMapper.readValue(boot.getResponse().getContentAsString(), Map.class);
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(adminSession, adminCsrf).header("X-CSRF-Token", adminCsrfToken)
                .content(objectMapper.writeValueAsString(
                        new PasswordChangeRequest((String) body.get("temporaryPassword"), "NewSecurePass1!"))))
                .andExpect(status().isOk());
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        for (String table : new String[]{"skill_revisions", "skill_access", "skills", "user_sessions", "users",
                "admin_audit_events"}) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Ordering covers the canonical FK set (revisions also cascade).
            }
        }
    }

    @Test
    @DisplayName("same-name re-upload publishes revisions; rollback restores the older package (I14)")
    void revisionHistoryAndRollback() throws Exception {
        byte[] pkg1 = zip("web-scraper/SKILL.md", SKILL_MD);
        String id = upload("1.0.0", pkg1);

        // Baseline: the first upload is revision 1, active; metadata only in JSON.
        String history = mockMvc.perform(get(revisionsUrl(id)).cookie(adminSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].revision").value(1))
                .andExpect(jsonPath("$[0].version").value("1.0.0")).andExpect(jsonPath("$[0].activatedAt").isNotEmpty())
                .andExpect(jsonPath("$[0].contentBytes").value(pkg1.length))
                .andExpect(jsonPath("$[0].activatedAt").isNotEmpty()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(history).doesNotContain("contentZip");

        // Re-upload same name: next revision, activated, metadata mirrored.
        byte[] pkg2 = zip("web-scraper/SKILL.md", SKILL_MD_V2);
        mockMvc.perform(post("/api/v1/admin/skills?version=1.1.0").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken).contentType("application/zip").content(pkg2))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value("1.1.0"))
                .andExpect(jsonPath("$.description").value("Scrapes pages AND sitemaps into markdown."));

        mockMvc.perform(get(revisionsUrl(id)).cookie(adminSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2))).andExpect(jsonPath("$[0].revision").value(2))
                .andExpect(jsonPath("$[0].activatedAt").isNotEmpty()).andExpect(jsonPath("$[1].revision").value(1))
                .andExpect(jsonPath("$[1].activatedAt").isEmpty());
        mockMvc.perform(get("/api/v1/skills/" + id + "/download").cookie(adminSession)).andExpect(status().isOk())
                .andExpect(content().bytes(pkg2));

        // Rollback: revision 1 becomes active again (no new number) and downloads
        // serve the original package; repeats are idempotent.
        mockMvc.perform(post(revisionsUrl(id) + "/1/activate").cookie(adminSession, adminCsrf).header("X-CSRF-Token",
                adminCsrfToken)).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.activatedAt").isNotEmpty());
        mockMvc.perform(get(revisionsUrl(id)).cookie(adminSession)).andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].activatedAt").isEmpty()).andExpect(jsonPath("$[1].activatedAt").isNotEmpty());
        mockMvc.perform(get("/api/v1/admin/skills").cookie(adminSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value("1.0.0"));
        mockMvc.perform(get("/api/v1/skills/" + id + "/download").cookie(adminSession)).andExpect(status().isOk())
                .andExpect(content().bytes(pkg1));
        mockMvc.perform(post(revisionsUrl(id) + "/1/activate").cookie(adminSession, adminCsrf).header("X-CSRF-Token",
                adminCsrfToken)).andExpect(status().isOk());

        // One publish + one activate (the idempotent repeat does not re-audit).
        org.assertj.core.api.Assertions.assertThat(auditCount("SKILL_REVISION_PUBLISH")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(auditCount("SKILL_REVISION_ACTIVATE")).isEqualTo(1);
    }

    @Test
    @DisplayName("revision endpoints validate: auth, unknown skill/revision, bad version")
    void revisionValidation() throws Exception {
        byte[] pkg = zip("web-scraper/SKILL.md", SKILL_MD);
        String id = upload("1.0.0", pkg);

        mockMvc.perform(get(revisionsUrl(id))).andExpect(status().isUnauthorized());
        mockMvc.perform(get(revisionsUrl(UUID.randomUUID().toString())).cookie(adminSession))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SKILL_NOT_FOUND"));
        mockMvc.perform(post(revisionsUrl(id) + "/9/activate").cookie(adminSession, adminCsrf).header("X-CSRF-Token",
                adminCsrfToken)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SKILL_REVISION_NOT_FOUND"));

        // Same-name upload with a non-semantic version is rejected before any write.
        mockMvc.perform(post("/api/v1/admin/skills?version=one").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken).contentType("application/zip").content(pkg))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VERSION_INVALID"));
        mockMvc.perform(get(revisionsUrl(id)).cookie(adminSession)).andExpect(jsonPath("$", hasSize(1)));
    }

    // ------------------------------------------------------------- helpers

    private String revisionsUrl(String skillId) {
        return "/api/v1/admin/skills/" + skillId + "/revisions";
    }

    private String upload(String version, byte[] pkg) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/skills?version=" + version)
                .cookie(adminSession, adminCsrf).header("X-CSRF-Token", adminCsrfToken)
                .contentType(MediaType.parseMediaType("application/zip")).content(pkg)).andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), Map.class).get("id").toString();
    }

    private long auditCount(String action) {
        return jdbc.queryForObject("SELECT count(*) FROM admin_audit_events WHERE action = :action",
                new MapSqlParameterSource("action", action), Long.class);
    }

    private static byte[] zip(String path, String content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry(path));
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private static Cookie cookie(MvcResult result, String name) {
        return java.util.stream.Stream.of(result.getResponse().getCookies()).filter(c -> c.getName().equals(name))
                .findFirst().orElse(null);
    }
}
