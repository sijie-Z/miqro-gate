package com.miqroera.miqrokey.controlplane.adversarial;

import com.miqroera.miqrokey.controlplane.dto.BootstrapRequest;
import com.miqroera.miqrokey.controlplane.dto.PasswordChangeRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import jakarta.servlet.http.Cookie;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Untrusted input at the SkillHub upload endpoint ({@code POST
 * /api/v1/admin/skills}, raw zip body): YAML global-tag gadgets, zip bombs and
 * path-traversal entry names must be refused through the real endpoint, and a
 * legal package must still pass — the reverse control that proves a rejection
 * is the validator working, not the endpoint being broken.
 *
 * <p>
 * The gadget case asserts a real side effect, not just the status code: the
 * payload names a class whose static initializer writes a marker file. If any
 * YAML layer ever instantiates it, the marker appears and the test says so,
 * instead of the gadget silently "being rejected" for an unrelated reason.
 * </p>
 */
@Tag("integration")
@DisplayName("Adversarial: SkillHub zip upload rejects gadgets, bombs and traversal (PostgreSQL)")
class SkillUploadAdversarialIntegrationTest extends AbstractAdversarialIntegrationTest {

    /** Written by the gadget class's static initializer if it is ever loaded. */
    private static final Path GADGET_MARKER = Path.of(System.getProperty("java.io.tmpdir"),
            "adv-gadget-marker-" + UUID.randomUUID() + ".txt");

    private Cookie adminSession;
    private Cookie adminCsrf;
    private String adminCsrfToken;

    @BeforeEach
    void setUp() throws Exception {
        Files.deleteIfExists(GADGET_MARKER);
        MvcResult boot = mockMvc
                .perform(post("/api/v1/auth/bootstrap").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BootstrapRequest(AdversarialTestSupport.secret(),
                                "adm_" + UUID.randomUUID().toString().substring(0, 8), "Admin"))))
                .andExpect(status().isCreated()).andReturn();
        adminSession = cookie(boot, "MIQROKEY_SESSION");
        adminCsrf = cookie(boot, "MIQROKEY_CSRF");
        adminCsrfToken = adminCsrf != null ? adminCsrf.getValue() : "";
        String tempPassword = map(boot).get("temporaryPassword").toString();
        mockMvc.perform(post("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                .cookie(adminSession, adminCsrf).header("X-CSRF-Token", adminCsrfToken)
                .content(objectMapper.writeValueAsString(new PasswordChangeRequest(tempPassword, "NewSecurePass1!"))))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // 1) YAML global-tag gadget
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a YAML global-tag gadget in SKILL.md is rejected, and the gadget class is never loaded")
    void yamlGlobalTagGadgetIsRejectedAndInert() throws Exception {
        String skillMd = """
                ---
                name: gadget-skill
                description: probe
                probe: !!com.miqroera.miqrokey.controlplane.adversarial.SkillUploadAdversarialIntegrationTest$GadgetProbe {}
                ---

                # Gadget
                """;
        MvcResult result = upload(1, zip("gadget-skill/SKILL.md", skillMd));

        assertThat(result.getResponse().getStatus())
                .as("gadget payload is rejected with 400 (body: %s)", result.getResponse().getContentAsString())
                .isEqualTo(400);
        JsonNode problem = body(result);
        assertThat(problem.get("code").asText()).isNotBlank();
        assertThat(Files.exists(GADGET_MARKER))
                .as("the gadget class's static initializer must never run (marker %s)", GADGET_MARKER).isFalse();
        assertThat(skillNames()).as("no skill was created by the rejected upload").isEmpty();
    }

    /**
     * Loaded only if a YAML layer ever instantiates it — the marker is the proof.
     */
    public static final class GadgetProbe {
        static {
            try {
                Files.writeString(GADGET_MARKER, "loaded");
            } catch (IOException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    // ------------------------------------------------------------------
    // 2) zip bombs
    // ------------------------------------------------------------------

    @Test
    @DisplayName("entry-count bomb (201 entries) is rejected with SKILL_TOO_MANY_ENTRIES")
    void entryCountBombIsRejected() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            writeEntry(zos, "web-scraper/SKILL.md", validSkillMd("web-scraper"));
            for (int i = 0; i < 200; i++) {
                writeEntry(zos, "web-scraper/filler-" + i + ".txt", "filler");
            }
        }
        MvcResult result = upload(1, out.toByteArray());
        assertThat(result.getResponse().getStatus())
                .as("201 entries is over the 200 cap (body: %s)", result.getResponse().getContentAsString())
                .isEqualTo(400);
        assertThat(body(result).get("code").asText()).isEqualTo("SKILL_TOO_MANY_ENTRIES");
        assertThat(skillNames()).isEmpty();
    }

    @Test
    @DisplayName("inflation bomb (tiny zip, 600KB SKILL.md) is rejected with SKILL_MD_TOO_LARGE")
    void skillMdInflationBombIsRejected() throws Exception {
        byte[] bomb = zip("web-scraper/SKILL.md", validSkillMd("web-scraper") + "a".repeat(600_000));
        assertThat(bomb.length).as("the bomb is small on the wire").isLessThan(100_000);
        MvcResult result = upload(1, bomb);
        assertThat(result.getResponse().getStatus())
                .as("decompressed SKILL.md over 512KB is refused (body: %s)", result.getResponse().getContentAsString())
                .isEqualTo(400);
        assertThat(body(result).get("code").asText()).isEqualTo("SKILL_MD_TOO_LARGE");
        assertThat(skillNames()).isEmpty();
    }

    @Test
    @DisplayName("oversized upload (>5MB on the wire) is rejected with SKILL_TOO_LARGE")
    void oversizedUploadIsRejected() throws Exception {
        byte[] incompressible = new byte[5_500_000];
        new SecureRandom().nextBytes(incompressible);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            writeEntry(zos, "web-scraper/SKILL.md", validSkillMd("web-scraper").getBytes(StandardCharsets.UTF_8));
            writeEntry(zos, "web-scraper/blob.bin", incompressible);
        }
        byte[] big = out.toByteArray();
        assertThat(big.length).as("the upload really exceeds the 5MB cap").isGreaterThan(5 * 1024 * 1024);
        MvcResult result = upload(1, big);
        assertThat(result.getResponse().getStatus())
                .as("over-cap upload is refused (body: %s)", result.getResponse().getContentAsString()).isEqualTo(400);
        assertThat(body(result).get("code").asText()).isEqualTo("SKILL_TOO_LARGE");
        assertThat(skillNames()).isEmpty();
    }

    /**
     * The decompressed-volume shape of a bomb: a handful of filler entries that
     * each inflate to megabytes while the package on the wire stays under 1MB. The
     * entry count is under the 200 cap and SKILL.md itself is small — the
     * decompressed-volume cap (#1233) is the guard that must refuse it, before
     * whoever downloads the package extracts it.
     */
    @Test
    @DisplayName("decompressed-volume bomb (~640MB from a sub-MB package) is rejected as SKILL_DECOMPRESSED_TOO_LARGE")
    void totalDecompressedVolumeBombIsRejected() throws Exception {
        int entries = 10;
        long perEntry = 64L * 1024 * 1024;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024 * 1024];
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            writeEntry(zos, "web-scraper/SKILL.md", validSkillMd("web-scraper"));
            for (int i = 0; i < entries; i++) {
                zos.putNextEntry(new ZipEntry("web-scraper/blob-" + i + ".bin"));
                for (long written = 0; written < perEntry; written += chunk.length) {
                    zos.write(chunk);
                }
                zos.closeEntry();
            }
        }
        byte[] bomb = out.toByteArray();
        assertThat(bomb.length).as("the package is tiny on the wire").isLessThan(1024 * 1024);
        assertThat((long) entries * perEntry).as("the package inflates to a bomb").isGreaterThan(500L * 1024 * 1024);

        MvcResult result = upload(1, bomb);
        assertThat(result.getResponse().getStatus())
                .as("a package inflating to ~%dMB from %dKB must be refused (body: %s)",
                        (long) entries * perEntry / 1024 / 1024, bomb.length / 1024,
                        result.getResponse().getContentAsString())
                .isEqualTo(400);
        assertThat(body(result).get("code").asText()).as("the refusal comes from the decompressed-volume cap (#1233)")
                .isEqualTo("SKILL_DECOMPRESSED_TOO_LARGE");
        assertThat(skillNames()).isEmpty();
    }

    // ------------------------------------------------------------------
    // 3) path traversal entry names
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a traversal entry inside the skill directory is rejected (SKILL_ENTRY_PATH_INVALID), not stored")
    void traversalInsideSkillDirectoryIsRejected() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            writeEntry(zos, "web-scraper/SKILL.md", validSkillMd("web-scraper"));
            writeEntry(zos, "web-scraper/../../escape.txt", "escaped");
        }
        MvcResult result = upload(1, out.toByteArray());
        assertThat(result.getResponse().getStatus())
                .as("a zip entry that resolves outside the skill directory must be refused (body: %s)",
                        result.getResponse().getContentAsString())
                .isEqualTo(400);
        assertThat(body(result).get("code").asText()).as("the refusal comes from the per-entry path guard (#1233)")
                .isEqualTo("SKILL_ENTRY_PATH_INVALID");
        assertThat(skillNames()).isEmpty();
    }

    @Test
    @DisplayName("a traversal root (../evil) is rejected")
    void traversalRootIsRejected() throws Exception {
        MvcResult result = upload(1, zip("../evil/SKILL.md", validSkillMd("evil")));
        assertThat(result.getResponse().getStatus())
                .as("../evil root must not become a skill (body: %s)", result.getResponse().getContentAsString())
                .isEqualTo(400);
        assertThat(skillNames()).isEmpty();
    }

    @Test
    @DisplayName("an absolute-path entry name is rejected")
    void absolutePathEntryIsRejected() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            writeEntry(zos, "web-scraper/SKILL.md", validSkillMd("web-scraper"));
            writeEntry(zos, "/etc/evil.txt", "escaped");
        }
        MvcResult result = upload(1, out.toByteArray());
        assertThat(result.getResponse().getStatus())
                .as("an absolute entry name must be refused (body: %s)", result.getResponse().getContentAsString())
                .isEqualTo(400);
        assertThat(skillNames()).isEmpty();
    }

    // ------------------------------------------------------------------
    // 4) reverse control: the legal package passes and downloads byte-for-byte
    // ------------------------------------------------------------------

    @Test
    @DisplayName("reverse control: a legal package is accepted and downloads byte-for-byte")
    void legalPackageIsAcceptedAndDownloadable() throws Exception {
        byte[] legal = zip("web-scraper/SKILL.md", validSkillMd("web-scraper"));
        MvcResult result = upload(1, legal);
        assertThat(result.getResponse().getStatus())
                .as("the endpoint works for legal input (body: %s)", result.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode created = body(result);
        assertThat(created.get("name").asText()).isEqualTo("web-scraper");
        assertThat(created.get("version").asText()).isEqualTo("1.0.0");
        assertThat(created.get("contentSha256").asText()).hasSize(64);
        assertThat(skillNames()).containsExactly("web-scraper");

        MvcResult download = mockMvc
                .perform(get("/api/v1/skills/" + created.get("id").asText() + "/download").cookie(adminSession))
                .andExpect(status().isOk()).andReturn();
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(legal);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private MvcResult upload(int version, byte[] zip) throws Exception {
        return mockMvc
                .perform(post("/api/v1/admin/skills").param("version", version + ".0.0").cookie(adminSession, adminCsrf)
                        .header("X-CSRF-Token", adminCsrfToken).contentType("application/zip").content(zip))
                .andReturn();
    }

    private java.util.List<String> skillNames() throws Exception {
        MvcResult listed = mockMvc.perform(get("/api/v1/admin/skills").cookie(adminSession)).andExpect(status().isOk())
                .andReturn();
        java.util.List<String> names = new java.util.ArrayList<>();
        for (JsonNode node : body(listed)) {
            names.add(node.get("name").asText());
        }
        return names;
    }

    private static String validSkillMd(String name) {
        return """
                ---
                name: %s
                description: Scrapes public web pages into markdown.
                author: Adversarial Suite
                license: MIT
                tags:
                  - scraping
                ---

                # %s
                """.formatted(name, name);
    }

    private static byte[] zip(String path, String content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            writeEntry(zos, path, content);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private static void writeEntry(ZipOutputStream zos, String path, String content) throws IOException {
        writeEntry(zos, path, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeEntry(ZipOutputStream zos, String path, byte[] content) throws IOException {
        zos.putNextEntry(new ZipEntry(path));
        zos.write(content);
        zos.closeEntry();
    }
}
