package com.miqroera.miqrokey.controlplane.service;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

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
 * {@code license}, {@code tags}) become catalog metadata. Entry names are
 * normalized and must stay inside that directory: {@code ..} segments,
 * absolute paths and drive letters are rejected (#1233, zip-slip). Bounds
 * guard oversized packages and zip bombs (we only read SKILL.md, never extract).
 */
public final class SkillZipValidator {

    /** Upper bound for an uploaded skill package. */
    public static final int MAX_ZIP_BYTES = 5 * 1024 * 1024;
    /** Upper bound for the SKILL.md file we read for metadata. */
    public static final int MAX_SKILL_MD_BYTES = 512 * 1024;
    /** Upper bound for zip entries (bomb guard). */
    public static final int MAX_ENTRIES = 200;
    /** Catalog bounds from the SkillHub spec (raw docs 20/28). */
    public static final int MAX_TAGS = 5;
    public static final int MAX_TAG_CHARS = 20;
    public static final int MAX_EXAMPLES = 10;
    public static final int MAX_EXAMPLE_CHARS = 512;

    private SkillZipValidator() {
    }

    /** Catalog metadata parsed from a validated skill package. */
    public record SkillMetadata(String name, String description, String author, String license, List<String> tags,
            List<String> examples) {
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
                String path = normalizedEntryPath(entry.getName());
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
                    // #427: the declared size above can be 0/-1 for streamed zips
                    // (data-descriptor mode) — bound the actual decompressed READ
                    // as well: a small zip must never inflate SKILL.md past the
                    // cap (zip-bomb guard the class claims).
                    byte[] skillMd = zis.readNBytes(MAX_SKILL_MD_BYTES + 1);
                    if (skillMd.length > MAX_SKILL_MD_BYTES) {
                        throw invalid("SKILL_MD_TOO_LARGE", "SKILL.md 超过大小上限。");
                    }
                    skillMdText = new String(skillMd, StandardCharsets.UTF_8);
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

    /**
     * Normalizes a zip entry name to a '/'-separated relative path, rejecting
     * anything that could escape the skill root when the package is extracted
     * downstream (#1233, zip-slip): absolute paths, drive letters, and any
     * {@code ..} segment — however the separator is spelled, since extractors
     * on Windows split on '\' too. Empty and '.' segments are dropped; nothing
     * else is rewritten, so accepted entries reach extractors unchanged.
     */
    private static String normalizedEntryPath(String name) {
        if (name == null) {
            throw invalidEntryPath();
        }
        String unified = name.replace('\\', '/');
        boolean driveLetter = unified.length() > 1 && unified.charAt(1) == ':' && Character.isLetter(unified.charAt(0));
        if (unified.startsWith("/") || driveLetter) {
            throw invalidEntryPath();
        }
        StringBuilder normalized = new StringBuilder();
        for (String segment : unified.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                throw invalidEntryPath();
            }
            if (normalized.length() > 0) {
                normalized.append('/');
            }
            normalized.append(segment);
        }
        if (normalized.length() == 0) {
            throw invalidEntryPath();
        }
        return normalized.toString();
    }

    private static SkillValidationException invalidEntryPath() {
        return invalid("SKILL_ENTRY_PATH_INVALID",
                "技能包内存在不安全的条目路径：条目名不允许 .. 段、绝对路径，或规范化后越出技能根目录。");
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
            Object loaded = yamlReader().load(yamlText);
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
        java.util.Set<String> tagSet = new java.util.LinkedHashSet<>();
        Object rawTags = meta.get("tags");
        if (rawTags instanceof List<?> list) {
            for (Object tag : list) {
                String value = str(tag);
                if (value != null && value.matches("[a-z0-9]+(-[a-z0-9]+)*")) {
                    tagSet.add(value);
                }
            }
        }
        if (tagSet.size() > MAX_TAGS || tagSet.stream().anyMatch(tag -> tag.length() > MAX_TAG_CHARS)) {
            throw invalid("SKILL_TAGS_INVALID",
                    "frontmatter 的 tags 最多 %d 个、每个不超过 %d 字符。".formatted(MAX_TAGS, MAX_TAG_CHARS));
        }
        List<String> examples = new ArrayList<>();
        Object rawExamples = meta.get("examples");
        if (rawExamples instanceof List<?> list) {
            for (Object example : list) {
                String value = str(example);
                if (value != null && !value.isBlank()) {
                    examples.add(value);
                }
            }
        }
        if (examples.size() > MAX_EXAMPLES
                || examples.stream().anyMatch(example -> example.length() > MAX_EXAMPLE_CHARS)) {
            throw invalid("SKILL_EXAMPLES_INVALID",
                    "frontmatter 的 examples 最多 %d 条、每条不超过 %d 字符。".formatted(MAX_EXAMPLES, MAX_EXAMPLE_CHARS));
        }
        return new SkillMetadata(name, description, str(meta.get("author")), str(meta.get("license")),
                new ArrayList<>(tagSet), examples);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    /** YAML reader for untrusted uploads: refuses class-instantiation tags. */
    private static Yaml yamlReader() {
        return new Yaml(new SafeConstructor(new LoaderOptions()));
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
