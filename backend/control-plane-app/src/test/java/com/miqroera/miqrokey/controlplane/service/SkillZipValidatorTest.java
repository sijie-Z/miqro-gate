package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.service.SkillZipValidator.SkillMetadata;
import com.miqroera.miqrokey.controlplane.service.SkillZipValidator.SkillValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SKILL.md / zip validation for SkillHub uploads (P2.2): single-root structure,
 * frontmatter parsing (name/description required, kebab-case, directory-name
 * match, reserved-word ban) and size/entry bounds.
 */
@DisplayName("SkillZipValidator")
class SkillZipValidatorTest {

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

    @Test
    @DisplayName("a valid skill package parses its frontmatter metadata")
    void validPackage() throws Exception {
        byte[] zip = zip("web-scraper/SKILL.md", SKILL_MD);

        SkillMetadata meta = SkillZipValidator.validate(zip);

        assertThat(meta.name()).isEqualTo("web-scraper");
        assertThat(meta.description()).startsWith("Scrapes public web pages");
        assertThat(meta.author()).isEqualTo("Platform Team");
        assertThat(meta.license()).isEqualTo("MIT");
        assertThat(meta.tags()).containsExactly("scraping", "web");
        assertThat(meta.examples()).isEmpty();
    }

    @Test
    @DisplayName("frontmatter examples parse; duplicate tags collapse")
    void examplesAndTagDedupe() throws Exception {
        String md = """
                ---
                name: web-scraper
                description: Scrapes public web pages into markdown.
                tags:
                  - web
                  - web
                  - scraping
                examples:
                  - 抓取 example.com 并转 markdown
                  - 批量抓取站点地图
                ---

                # body
                """;

        SkillMetadata meta = SkillZipValidator.validate(zip("web-scraper/SKILL.md", md));

        assertThat(meta.examples()).containsExactly("抓取 example.com 并转 markdown", "批量抓取站点地图");
        assertThat(meta.tags()).containsExactly("web", "scraping");
    }

    @Test
    @DisplayName("tag and example limits are enforced (raw doc 20)")
    void tagsAndExamplesLimitsEnforced() throws Exception {
        String tooManyTags = SKILL_MD.replace("tags:\n  - scraping\n  - web",
                "tags:\n  - a\n  - b\n  - c\n  - d\n  - e\n  - f");
        assertThatThrownBy(() -> SkillZipValidator.validate(zip("web-scraper/SKILL.md", tooManyTags)))
                .isInstanceOf(SkillValidationException.class).hasMessageContaining("tags");

        String longTag = SKILL_MD.replace("- scraping", "- " + "x".repeat(21));
        assertThatThrownBy(() -> SkillZipValidator.validate(zip("web-scraper/SKILL.md", longTag)))
                .isInstanceOf(SkillValidationException.class).hasMessageContaining("tags");

        StringBuilder tooManyExamples = new StringBuilder("examples:\n");
        for (int i = 0; i < 11; i++) {
            tooManyExamples.append("  - example ").append(i).append('\n');
        }
        String withManyExamples = SKILL_MD.replace("---\n\n# Web Scraper", tooManyExamples + "---\n\n# Web Scraper");
        assertThatThrownBy(() -> SkillZipValidator.validate(zip("web-scraper/SKILL.md", withManyExamples)))
                .isInstanceOf(SkillValidationException.class).hasMessageContaining("examples");

        String longExample = SKILL_MD.replace("---\n\n# Web Scraper",
                "examples:\n  - " + "y".repeat(513) + "\n---\n\n# Web Scraper");
        assertThatThrownBy(() -> SkillZipValidator.validate(zip("web-scraper/SKILL.md", longExample)))
                .isInstanceOf(SkillValidationException.class).hasMessageContaining("examples");
    }

    @Test
    @DisplayName("a BOM in SKILL.md is tolerated")
    void bomTolerated() throws Exception {
        byte[] zip = zip("web-scraper/SKILL.md", "﻿" + SKILL_MD);

        assertThat(SkillZipValidator.validate(zip).name()).isEqualTo("web-scraper");
    }

    @Test
    @DisplayName("missing SKILL.md is rejected")
    void missingSkillMdRejected() throws Exception {
        byte[] zip = zip("web-scraper/scripts/run.py", "print('hi')");

        assertThatThrownBy(() -> SkillZipValidator.validate(zip)).isInstanceOf(SkillValidationException.class)
                .hasMessageContaining("SKILL.md");
    }

    @Test
    @DisplayName("a directory name mismatching the frontmatter name is rejected")
    void nameMismatchRejected() throws Exception {
        byte[] zip = zip("other-name/SKILL.md", SKILL_MD);

        assertThatThrownBy(() -> SkillZipValidator.validate(zip)).isInstanceOf(SkillValidationException.class)
                .hasMessageContaining("目录名一致");
    }

    @Test
    @DisplayName("invalid names (uppercase, reserved words) are rejected")
    void invalidNameRejected() throws Exception {
        byte[] zip = zip("web-scraper/SKILL.md", SKILL_MD.replace("name: web-scraper", "name: Web-Scraper"));
        assertThatThrownBy(() -> SkillZipValidator.validate(zip)).isInstanceOf(SkillValidationException.class)
                .hasMessageContaining("kebab-case");

        byte[] reserved = zip("web-scraper/SKILL.md", SKILL_MD.replace("name: web-scraper", "name: claude-helper"));
        assertThatThrownBy(() -> SkillZipValidator.validate(reserved)).isInstanceOf(SkillValidationException.class)
                .hasMessageContaining("保留词");
    }

    @Test
    @DisplayName("a missing description is rejected")
    void missingDescriptionRejected() throws Exception {
        byte[] zip = zip("web-scraper/SKILL.md",
                SKILL_MD.replace("description: Scrapes public web pages into markdown.\n", ""));

        assertThatThrownBy(() -> SkillZipValidator.validate(zip)).isInstanceOf(SkillValidationException.class)
                .hasMessageContaining("description");
    }

    @Test
    @DisplayName("no frontmatter is rejected")
    void noFrontmatterRejected() throws Exception {
        byte[] zip = zip("web-scraper/SKILL.md", "# Web Scraper\n\nJust a skill.\n");

        assertThatThrownBy(() -> SkillZipValidator.validate(zip)).isInstanceOf(SkillValidationException.class)
                .hasMessageContaining("frontmatter");
    }

    @Test
    @DisplayName("multiple root directories are rejected")
    void multipleRootsRejected() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry("web-scraper/SKILL.md"));
            zos.write(SKILL_MD.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("other/README.md"));
            zos.write("x".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        assertThatThrownBy(() -> SkillZipValidator.validate(out.toByteArray()))
                .isInstanceOf(SkillValidationException.class).hasMessageContaining("一个技能目录");
    }

    @Test
    @DisplayName("not a zip is rejected")
    void notZipRejected() throws Exception {
        assertThatThrownBy(() -> SkillZipValidator.validate("not a zip".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(SkillValidationException.class).hasMessageContaining("zip");
    }

    @Test
    @DisplayName("an oversized SKILL.md is rejected on a streamed zip (data-descriptor sizes) (#427)")
    void oversizedSkillMdRejected() throws Exception {
        // The ZipOutputStream helper writes data-descriptor entries: the local
        // header carries 0/-1 sizes, so the declared-size check alone never
        // fires — the bounded read must catch it with the size error.
        byte[] oversized = zip("web-scraper/SKILL.md", "a".repeat(SkillZipValidator.MAX_SKILL_MD_BYTES + 1));
        assertThatThrownBy(() -> SkillZipValidator.validate(oversized)).isInstanceOf(SkillValidationException.class)
                .hasMessageContaining("超过大小上限");
    }

    @Test
    @DisplayName("oversized packages are rejected")
    void oversizedRejected() throws Exception {
        byte[] huge = new byte[SkillZipValidator.MAX_ZIP_BYTES + 1];
        assertThatThrownBy(() -> SkillZipValidator.validate(huge)).isInstanceOf(SkillValidationException.class)
                .hasMessageContaining("上限");
    }

    @Test
    @DisplayName("frontmatter 里的 YAML 全局标签被拒绝（反序列化 gadget 载荷）")
    void rejectsYamlGlobalTags() {
        // A global tag is the vector that turns YAML parsing into class instantiation;
        // the validator must keep rejecting it regardless of the library's defaults.
        String md = """
                ---
                name: web-scraper
                description: !!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL ["http://127.0.0.1:9/"]]]]
                tags:
                  - scraping
                ---
                """;

        assertThatThrownBy(() -> SkillZipValidator.validate(zip("web-scraper/SKILL.md", md)))
                .isInstanceOf(SkillValidationException.class);
    }

    @Test
    @DisplayName("entry names escaping the skill root (.. segments, absolute paths) are rejected (#1233)")
    void traversalEntryNamesAreRejected() throws Exception {
        // The single-root check only looks at the first path segment, so
        // `web-scraper/../../escape.txt` keeps the root "web-scraper" and was accepted —
        // yet every extractor resolves it outside the skill directory (zip-slip).
        for (String evilPath : new String[] {
                "web-scraper/../../escape.txt",
                "web-scraper/../escape.txt", // normalizes outside the root
                "web-scraper/docs/../notes.md", // '..' rejected even when it normalizes back inside
                "web-scraper/scripts/../../../escape.txt",
                "/etc/escape.txt",
                "web-scraper\\..\\..\\escape.txt",
                "..\\escape.txt",
        }) {
            byte[] pkg = zipOf(entry("web-scraper/SKILL.md", SKILL_MD), entry(evilPath, "evil"));

            assertThatThrownBy(() -> SkillZipValidator.validate(pkg)).as("entry name %s", evilPath)
                    .isInstanceOf(SkillValidationException.class)
                    .satisfies(thrown -> assertThat(((SkillValidationException) thrown).code())
                            .isEqualTo("SKILL_ENTRY_PATH_INVALID"));
        }
    }

    @Test
    @DisplayName("counter-control: a legal package with nested directories is accepted (#1233)")
    void legalNestedDirectoriesAreAccepted() throws Exception {
        byte[] pkg = zipOf(
                entry("web-scraper/SKILL.md", SKILL_MD),
                entry("web-scraper/scripts/run.py", "print('hi')"),
                entry("web-scraper/reference/notes/guide.md", "# guide"));

        assertThat(SkillZipValidator.validate(pkg).name()).isEqualTo("web-scraper");
    }

    @Test
    @DisplayName("a decompressed-volume bomb (638 KB -> ~640 MB) is rejected (#1233)")
    void decompressedVolumeBombIsRejected() throws Exception {
        // Ten entries inflating to 64 MB each: ~640 MB decompressed from well under
        // MAX_ZIP_BYTES, 11 entries, small SKILL.md — every pre-#1233 bound is blind to it.
        // The declared ZipEntry.getSize() is -1 for these data-descriptor entries (the JDK
        // streaming writer never back-patches sizes), so it must not be read as "0 bytes".
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024 * 1024];
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry("web-scraper/SKILL.md"));
            zos.write(SKILL_MD.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            for (int i = 0; i < 10; i++) {
                zos.putNextEntry(new ZipEntry("web-scraper/blob-" + i + ".bin"));
                for (int j = 0; j < 64; j++) {
                    zos.write(chunk);
                }
                zos.closeEntry();
            }
        }
        byte[] bomb = out.toByteArray();
        assertThat(bomb.length).as("wire bytes").isLessThan(SkillZipValidator.MAX_ZIP_BYTES);

        assertThatThrownBy(() -> SkillZipValidator.validate(bomb))
                .isInstanceOf(SkillValidationException.class)
                .satisfies(thrown -> assertThat(((SkillValidationException) thrown).code())
                        .isEqualTo("SKILL_DECOMPRESSED_TOO_LARGE"));
    }

    @Test
    @DisplayName("counter-control: a normal package with a small asset is accepted (#1233)")
    void legalPackageWithAssetsIsAccepted() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024 * 1024];
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry("web-scraper/SKILL.md"));
            zos.write(SKILL_MD.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("web-scraper/assets/data.bin"));
            for (int j = 0; j < 4; j++) {
                zos.write(chunk); // 4 MB decompressed — far below the cap
            }
            zos.closeEntry();
        }

        assertThat(SkillZipValidator.validate(out.toByteArray()).name()).isEqualTo("web-scraper");
    }

    private static byte[] zipOf(TestEntry... entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (TestEntry entry : entries) {
                zos.putNextEntry(new ZipEntry(entry.path()));
                zos.write(entry.content());
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static TestEntry entry(String path, String content) {
        return new TestEntry(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private record TestEntry(String path, byte[] content) {
    }

    private static byte[] zip(String path, String content) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry(path));
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return out.toByteArray();
    }
}
