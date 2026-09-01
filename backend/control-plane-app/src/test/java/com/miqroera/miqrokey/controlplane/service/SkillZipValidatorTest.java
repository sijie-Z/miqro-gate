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
    @DisplayName("oversized packages are rejected")
    void oversizedRejected() throws Exception {
        byte[] huge = new byte[SkillZipValidator.MAX_ZIP_BYTES + 1];
        assertThatThrownBy(() -> SkillZipValidator.validate(huge)).isInstanceOf(SkillValidationException.class)
                .hasMessageContaining("上限");
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
