package com.miqroera.miqrokey.controlplane.service;

import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Validates SkillHub uploads against the Anthropic Agent Skills format (P2.2):
 * the zip must hold exactly one skill directory whose name matches the
 * {@code SKILL.md} frontmatter {@code name} (kebab-case), with a non-blank
 * {@code description}. Optional frontmatter fields ({@code author},
 * {@code license}, {@code tags}) become catalog metadata. Bounds guard
 * oversized packages and zip bombs (we only read SKILL.md, never extract).
 */
public final class SkillZipValidator {

    /** Upper bound for an uploaded skill package. */
    public static final int MAX_ZIP_BYTES = 5 * 1024 * 1024;
    /** Upper bound for the SKILL.md file we read for metadata. */
    public static final int MAX_SKILL_MD_BYTES = 512 * 1024;
    /** Upper bound for zip entries (bomb guard). */
    public static final int MAX_ENTRIES = 200;

    private SkillZipValidator() {
    }

    /** Catalog metadata parsed from a validated skill package. */
    public record SkillMetadata(String name, String description, String author, String license, List<String> tags) {
    }

    public static SkillMetadata validate(byte[] zip) {
        if (zip == null || zip.length == 0) {
            throw invalid("SKILL_EMPTY", "上传内容为空。");
        }
        if (zip.length > MAX_ZIP_BYTES) {
            throw invalid("SKILL_TOO_LARGE", "技能包超过 %d MB 上限。".formatted(MAX_ZIP_BYTES / 1024 / 1024));
        }
        String rootDir = null;
        String skillMdText = null;
        int entries = 0;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw invalid("SKILL_TOO_MANY_ENTRIES", "技能包条目数超过上限。");
                }
                String path = entry.getName();
                int slash = path.indexOf('/');
                String top = slash > 0 ? path.substring(0, slash) : path;
                if (rootDir == null) {
                    rootDir = top;
                } else if (!top.equals(rootDir)) {
                    throw invalid("SKILL_MULTIPLE_ROOTS", "技能包必须只包含一个技能目录（根目录下不能有多余文件）。");
                }
                if (!entry.isDirectory() && path.equals(rootDir + "/SKILL.md")) {
                    if (entry.getSize() > MAX_SKILL_MD_BYTES) {
                        throw invalid("SKILL_MD_TOO_LARGE", "SKILL.md 超过大小上限。");
                    }
                    skillMdText = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (java.io.IOException e) {
            throw invalid("SKILL_ZIP_INVALID", "技能包不是有效的 zip 文件。");
        }
        if (rootDir == null) {
            // No entries at all: shorter than the minimal zip (22-byte EOCD)
            // cannot be a zip; at least that long it is a genuinely empty
            // package.
            throw zip.length < 22 ? invalid("SKILL_ZIP_INVALID", "技能包不是有效的 zip 文件。") : invalid("SKILL_EMPTY", "技能包为空。");
        }
        if (skillMdText == null) {
            throw invalid("SKILL_MD_MISSING", "技能目录必须包含 SKILL.md。");
        }
        return parseFrontmatter(rootDir, skillMdText);
    }

    private static SkillMetadata parseFrontmatter(String rootDir, String skillMd) {
        String body = skillMd;
        if (body.startsWith("﻿")) {
            body = body.substring(1); // tolerate a BOM
        }
        if (!body.startsWith("---")) {
            throw invalid("SKILL_FRONTMATTER_INVALID", "SKILL.md 必须以 YAML frontmatter（---）开头。");
        }
        int yamlEnd = body.indexOf("\n---", 3);
        String yamlText = yamlEnd > 0 ? body.substring(3, yamlEnd) : body.substring(3);
        Map<String, Object> meta;
        try {
            Object loaded = new Yaml().load(yamlText);
            if (!(loaded instanceof Map)) {
                throw invalid("SKILL_FRONTMATTER_INVALID", "SKILL.md frontmatter 必须是 YAML 映射。");
            }
            meta = (Map<String, Object>) loaded;
        } catch (Exception e) {
            throw invalid("SKILL_FRONTMATTER_INVALID", "SKILL.md frontmatter 不是合法的 YAML。");
        }
        String name = str(meta.get("name"));
        String description = str(meta.get("description"));
        if (name == null || !name.matches("[a-z0-9]+(-[a-z0-9]+)*")) {
            throw invalid("SKILL_NAME_INVALID", "frontmatter 的 name 必须是小写 kebab-case（字母数字与连字符）。");
        }
        if (name.contains("claude") || name.contains("anthropic")) {
            throw invalid("SKILL_NAME_INVALID", "name 不能包含 claude/anthropic 保留词。");
        }
        if (!name.equals(rootDir)) {
            throw invalid("SKILL_NAME_MISMATCH", "frontmatter 的 name 必须与技能目录名一致（%s）。".formatted(rootDir));
        }
        if (description == null || description.isBlank() || description.length() > 1024) {
            throw invalid("SKILL_DESCRIPTION_INVALID", "frontmatter 的 description 必填且不超过 1024 字符。");
        }
        List<String> tags = new ArrayList<>();
        Object rawTags = meta.get("tags");
        if (rawTags instanceof List<?> list) {
            for (Object tag : list) {
                String value = str(tag);
                if (value != null && value.matches("[a-z0-9]+(-[a-z0-9]+)*")) {
                    tags.add(value);
                }
            }
        }
        return new SkillMetadata(name, description, str(meta.get("author")), str(meta.get("license")), tags);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static SkillValidationException invalid(String code, String detail) {
        return new SkillValidationException(code, detail);
    }

    /** Upload rejected by format validation (mapped to 400 with the code). */
    public static final class SkillValidationException extends RuntimeException {
        private final String code;

        SkillValidationException(String code, String detail) {
            super(detail);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
