package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.AbstractControlPlaneIntegrationTest;
import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.LoginRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import com.miqroera.miqrokey.domain.service.PasswordHasher;
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

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SkillHub catalog (P2.2/P2.3): upload validation, visibility for every
 * signed-in user, download gating by TEAM/PROJECT grants, archive and re-upload
 * upsert.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Tag("integration")
@DisplayName("SkillHub API integration tests (PostgreSQL)")
class SkillApiIntegrationTest {

    static {
        AbstractControlPlaneIntegrationTest.POSTGRES.getJdbcUrl();
    }

    /** Seed tenant of the single-tenant deployment (bootstrap creates it). */
    private static final UUID SEED_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");

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
    @Autowired
    PasswordHasher passwordHasher;

    private Cookie adminSession;
    private Cookie adminCsrf;
    private String adminCsrfToken;
    private Cookie memberSession;
    private Cookie outsiderSession;
    private UUID projectId;

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

        projectId = seedProject("SKILL", "Skill Project");
        memberSession = seedAndLogin("member_user");
        outsiderSession = seedAndLogin("outsider_user");
        jdbc.update("""
                INSERT INTO project_memberships (tenant_id, project_id, user_id)
                VALUES (:tenantId, :projectId, :memberId)
                """, new MapSqlParameterSource("tenantId", SEED_TENANT).addValue("projectId", projectId)
                .addValue("memberId", memberId()));
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        for (String table : new String[]{"skill_access", "skills", "project_memberships", "team_memberships", "teams",
                "projects", "user_sessions", "users", "admin_audit_events"}) {
            try {
                jdbc.update("DELETE FROM " + table, new MapSqlParameterSource());
            } catch (Exception ignored) {
                // Ordering covers the canonical FK set.
            }
        }
    }

    private UUID memberId() {
        return jdbc.queryForObject("SELECT id FROM users WHERE username = 'member_user'", new MapSqlParameterSource(),
                UUID.class);
    }

    private UUID seedProject(String code, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, tenant_id, code, name, status, project_tag, version)
                VALUES (:id, :tenantId, :code, :name, 'ACTIVE', :tag, 0)
                """, new MapSqlParameterSource("id", id).addValue("tenantId", SEED_TENANT).addValue("code", code)
                .addValue("name", name).addValue("tag", code.toLowerCase()));
        return id;
    }

    private Cookie seedAndLogin(String username) throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, tenant_id, username, display_name, password_hash, role, status,
                                   must_change_password, version)
                VALUES (:id, :tenantId, :username, 'User', :hash, 'USER', 'ACTIVE', FALSE, 0)
                """, new MapSqlParameterSource("id", id).addValue("tenantId", SEED_TENANT)
                .addValue("username", username).addValue("hash", passwordHasher.hash("NewSecurePass1!")));
        MvcResult login = mockMvc
                .perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, "NewSecurePass1!"))))
                .andExpect(status().isOk()).andReturn();
        return cookie(login, "MIQROKEY_SESSION");
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("upload validates the package and makes it visible to every signed-in user")
    void uploadValidatesAndLists() throws Exception {
        // Valid upload parses frontmatter metadata.
        mockMvc.perform(post("/api/v1/admin/skills?version=1.0.0").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken).contentType("application/zip")
                .content(zip("web-scraper/SKILL.md", SKILL_MD))).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("web-scraper"))
                .andExpect(jsonPath("$.description").value("Scrapes public web pages into markdown."))
                .andExpect(jsonPath("$.author").value("Platform Team")).andExpect(jsonPath("$.version").value("1.0.0"))
                .andExpect(jsonPath("$.tags[0]").value("scraping")).andExpect(jsonPath("$.contentSha256").isNotEmpty());

        // Signed-in users see the catalog (visibility is never gated).
        mockMvc.perform(get("/api/v1/skills").cookie(memberSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].name").value("web-scraper"));
        mockMvc.perform(get("/api/v1/skills").cookie(outsiderSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        // Anonymous is rejected.
        mockMvc.perform(get("/api/v1/skills")).andExpect(status().isUnauthorized());

        // Invalid uploads are rejected with stable codes.
        mockMvc.perform(post("/api/v1/admin/skills?version=1.0.0").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken).contentType("application/zip")
                .content(zip("web-scraper/scripts/run.py", "print(1)"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SKILL_MD_MISSING"));
        mockMvc.perform(post("/api/v1/admin/skills?version=abc").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken).contentType("application/zip")
                .content(zip("web-scraper/SKILL.md", SKILL_MD))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VERSION_INVALID"));
    }

    @Test
    @DisplayName("downloads are gated by grants: public, scoped, admin bypass")
    void downloadGatesByGrant() throws Exception {
        // Public skill (no grants): anyone may download, byte-for-byte.
        byte[] pkg = zip("web-scraper/SKILL.md", SKILL_MD);
        String publicId = upload("web-scraper", "1.0.0", pkg);
        mockMvc.perform(get("/api/v1/skills/" + publicId + "/download").cookie(outsiderSession))
                .andExpect(status().isOk()).andExpect(content().bytes(pkg));

        // Scoped skill: only project members and admins may download.
        String scopedId = upload("private-helper", "2.0.0",
                zip("private-helper/SKILL.md", SKILL_MD.replace("name: web-scraper", "name: private-helper")));
        mockMvc.perform(put("/api/v1/admin/skills/" + scopedId + "/access").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("[{\"scopeType\":\"PROJECT\",\"scopeId\":\"" + projectId + "\"}]")).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        mockMvc.perform(get("/api/v1/skills/" + scopedId + "/download").cookie(memberSession))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/skills/" + scopedId + "/download").cookie(outsiderSession))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SKILL_DOWNLOAD_FORBIDDEN"));
        mockMvc.perform(get("/api/v1/skills/" + scopedId + "/download").cookie(adminSession))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("archiving hides the skill; re-upload restores it as the new version")
    void archiveAndReupload() throws Exception {
        String id = upload("web-scraper", "1.0.0", zip("web-scraper/SKILL.md", SKILL_MD));

        mockMvc.perform(post("/api/v1/admin/skills/" + id + "/archive").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
        mockMvc.perform(get("/api/v1/skills").cookie(memberSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/v1/skills/" + id).cookie(memberSession)).andExpect(status().isNotFound());

        // Re-upload with the same name reactivates the entry at the new version.
        mockMvc.perform(post("/api/v1/admin/skills?version=1.1.0").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken).contentType("application/zip")
                .content(zip("web-scraper/SKILL.md", SKILL_MD))).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1.1.0")).andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(get("/api/v1/skills").cookie(memberSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @DisplayName("access grants validate their scopes")
    void accessValidation() throws Exception {
        String id = upload("web-scraper", "1.0.0", zip("web-scraper/SKILL.md", SKILL_MD));

        mockMvc.perform(put("/api/v1/admin/skills/" + id + "/access").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("[{\"scopeType\":\"PROJECT\",\"scopeId\":\"" + UUID.randomUUID() + "\"}]"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SCOPE_INVALID"));
        mockMvc.perform(put("/api/v1/admin/skills/" + id + "/access").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("[{\"scopeType\":\"DEPARTMENT\",\"scopeId\":\"" + projectId + "\"}]"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SCOPE_INVALID"));
        mockMvc.perform(put("/api/v1/admin/skills/" + UUID.randomUUID() + "/access").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken).contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SKILL_NOT_FOUND"));

        // Clearing grants makes the skill public again.
        mockMvc.perform(put("/api/v1/admin/skills/" + id + "/access").cookie(adminSession, adminCsrf)
                .header("X-CSRF-Token", adminCsrfToken).contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    private String upload(String name, String version, byte[] pkg) throws Exception {
        MvcResult result = mockMvc
                .perform(post("/api/v1/admin/skills?version=" + version).cookie(adminSession, adminCsrf)
                        .header("X-CSRF-Token", adminCsrfToken).contentType("application/zip").content(pkg))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), Map.class).get("id").toString();
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
