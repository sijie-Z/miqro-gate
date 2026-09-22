package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.service.SkillZipValidator.SkillMetadata;
import com.miqroera.miqrokey.controlplane.service.SkillZipValidator.SkillValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
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
        // `web-scraper/../../escape.txt` keeps the root "web-scraper" and was
        // accepted — yet extractors resolve it outside the skill directory.
        assertEntryPathRejected("web-scraper/../../escape.txt");
        assertEntryPathRejected("web-scraper/../escape.txt"); // normalizes outside the root
        assertEntryPathRejected("web-scraper/docs/../notes.md"); // '..' rejected even when it stays inside
        assertEntryPathRejected("web-scraper/scripts/../../../escape.txt");
        assertEntryPathRejected("/etc/escape.txt");
        assertEntryPathRejected("web-scraper\\..\\..\\escape.txt");
        assertEntryPathRejected("..\\escape.txt");
    }

    private static void assertEntryPathRejected(String evilPath) throws Exception {
        byte[] pkg = zipOf(entry("web-scraper/SKILL.md", SKILL_MD), entry(evilPath, "evil"));

        assertThatThrownBy(() -> SkillZipValidator.validate(pkg)).as("entry name %s", evilPath)
                .isInstanceOf(SkillValidationException.class)
                .satisfies(thrown -> assertThat(((SkillValidationException) thrown).code())
                        .isEqualTo("SKILL_ENTRY_PATH_INVALID"));
    }

    @Test
    @DisplayName("counter-control: a legal package with nested directories is accepted (#1233)")
    void legalNestedDirectoriesAreAccepted() throws Exception {
        byte[] pkg = zipOf(entry("web-scraper/SKILL.md", SKILL_MD), entry("web-scraper/scripts/run.py", "print('hi')"),
                entry("web-scraper/reference/notes/guide.md", "# guide"));

        assertThat(SkillZipValidator.validate(pkg).name()).isEqualTo("web-scraper");
    }

    @Test
    @DisplayName("a decompressed-volume bomb (638 KB -> ~640 MB) is rejected (#1233)")
    void decompressedVolumeBombIsRejected() throws Exception {
        // Ten entries inflating to 64 MB each: ~640 MB decompressed from well
        // under MAX_ZIP_BYTES, 11 entries, small SKILL.md — every pre-#1233
        // bound is blind to it. The declared ZipEntry.getSize() is -1 for these
        // data-descriptor entries (the JDK streaming writer never back-patches
        // sizes), so it must not be read as "0 bytes".
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

        assertThatThrownBy(() -> SkillZipValidator.validate(bomb)).isInstanceOf(SkillValidationException.class)
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

    // ------------------------------------------------------------------
    // #1242: the local-header stream and the central directory must describe
    // the same entries. The three forms below are hand-assembled zips whose
    // two views disagree; mainstream extractors (python zipfile, .NET,
    // PowerShell Expand-Archive) follow the central directory and inflate
    // ~640 MiB from a ~650 KB package, while a local-header walker (the
    // pre-#1242 validator) sees ~650 KB and accepts. Each must be refused
    // fail-closed; the raw honest controls (sizes upfront, no data
    // descriptor — what python's zipfile writes) must still be accepted.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("form A: local STORED / central directory DEFLATED divergence is rejected (#1242)")
    void formARejected() {
        assertStructureRejected(formA(), "form A (views disagree on method/size)");
    }

    @Test
    @DisplayName("form B: central directory offset into an embedded fake local header is rejected (#1242)")
    void formBRejected() {
        assertStructureRejected(formB(), "form B (ghost local header inside carrier data)");
    }

    @Test
    @DisplayName("form C: a second (evil) central directory next to the EOCD is rejected (#1242)")
    void formCRejected() {
        assertStructureRejected(formC(), "form C (double central directory)");
    }

    @Test
    @DisplayName("fail-closed: zip64 markers are refused (#1242)")
    void zip64Rejected() {
        // (a) a zip64 EOCD locator (20 bytes: sig, disk, 8-byte offset, disk count)
        // right before the EOCD, as a zip64 archive would carry it.
        byte[] base = rawHonestDeflate();
        byte[] upToEocd = java.util.Arrays.copyOf(base, base.length - 22);
        byte[] eocd = java.util.Arrays.copyOfRange(base, base.length - 22, base.length);
        byte[] locator = bytes(le32(0x07064B50L), le32(0), le32(8), le32(0), le32(1));
        assertCode(bytes(upToEocd, locator, eocd), "SKILL_ZIP64_UNSUPPORTED");

        // (b) zip64 sentinel values in the EOCD entry count.
        byte[] sentinel = rawHonestDeflate();
        sentinel[sentinel.length - 22 + 8] = (byte) 0xFF;
        sentinel[sentinel.length - 22 + 9] = (byte) 0xFF;
        assertCode(sentinel, "SKILL_ZIP64_UNSUPPORTED");
    }

    @Test
    @DisplayName("fail-closed: a multi-disk archive is refused (#1242)")
    void multiDiskRejected() {
        byte[] pkg = rawHonestDeflate();
        pkg[pkg.length - 22 + 4] = 1; // EOCD disk number
        assertCode(pkg, "SKILL_ZIP_STRUCTURE_INVALID");
    }

    @Test
    @DisplayName("fail-closed: a STORED entry with a data descriptor is refused (#1242)")
    void storedWithDescriptorRejected() {
        // Impossible to locate where a stored entry's data ends without trusting
        // the very fields the descriptor is supposed to verify.
        byte[] md = SKILL_MD.getBytes(StandardCharsets.UTF_8);
        byte[] local = rawLocal("web-scraper/SKILL.md", 0, 8, 0, 0, 0);
        byte[] cd = rawCd("web-scraper/SKILL.md", 0, 8, crc32(md), md.length, md.length, 0);
        assertCode(bytes(local, md, rawDescriptor(crc32(md), md.length, md.length), cd,
                rawEocd(1, cd.length, local.length + md.length + 16)), "SKILL_ZIP_STRUCTURE_INVALID");
    }

    @Test
    @DisplayName("counter-control: trailing block padding after the EOCD (bsdtar streaming style) is accepted (#1242 H1)")
    void trailingBlockPaddingAccepted() {
        // bsdtar/libarchive pads its streaming output to a 10 KiB block, leaving
        // thousands of NUL bytes after the EOCD record. python, java.util.zip
        // .ZipFile and .NET all read such packages; the padding carries no EOCD
        // signature, so the package stays accepted (regression found by the
        // independent adversarial verification, fixed in the H1 round).
        byte[] pkg = bytes(rawHonestDeflate(), new byte[9316]);

        assertThat(SkillZipValidator.validate(pkg).name()).isEqualTo("web-scraper");
    }

    @Test
    @DisplayName("fail-closed: a second EOCD (bare signature or smuggled record) in the trailing bytes is refused (#1242 H1)")
    void secondEocdInTrailingBytesRejected() {
        byte[] pkg = rawHonestDeflate();
        byte[] eocdRecord = java.util.Arrays.copyOfRange(pkg, pkg.length - 22, pkg.length);
        // Padding may not smuggle another EOCD: a bare signature ...
        assertCode(bytes(pkg, new byte[100], le32(0x06054B50L), new byte[100]), "SKILL_ZIP_STRUCTURE_INVALID");
        // ... or a copy of the whole record somewhere in the trailing bytes.
        assertCode(bytes(pkg, new byte[100], eocdRecord, new byte[100]), "SKILL_ZIP_STRUCTURE_INVALID");
    }

    @Test
    @DisplayName("fail-closed: a declared csize running past the end of the file is refused cleanly (#1242 H2)")
    void declaredCsizePastEndRejected() {
        // One mutated 4-byte field on an honest package. Pre-#1242 this was a
        // clean 400; the first cut of verifyTwoViews handed the declared size
        // straight to Inflater.setInput and leaked an uncaught
        // ArrayIndexOutOfBoundsException, which the controller maps to a 500.
        byte[] pkg = rawHonestDeflate();
        pkg[18] = (byte) 0x00; // entry 0 local header csize -> 0x00F00000
        pkg[19] = (byte) 0x00;
        pkg[20] = (byte) 0xF0;
        pkg[21] = (byte) 0x00;

        assertCode(pkg, "SKILL_ZIP_STRUCTURE_INVALID");
    }

    @Test
    @DisplayName("fail-closed: a central directory that lies about an entry's size is rejected (#1242)")
    void cdSizeLieRejected() {
        // The local header is honest (and so is the data), but the directory
        // under-reports the inflated size. The streaming view never reads the
        // directory, so pre-#1242 the package was accepted while a
        // directory-based reader saw a different entry description.
        byte[] md = SKILL_MD.getBytes(StandardCharsets.UTF_8);
        byte[] mdDef = deflate(md);
        byte[] first = bytes(rawLocal("web-scraper/SKILL.md", 8, 0, crc32(md), mdDef.length, md.length), mdDef);
        byte[] second = bytes(rawLocal("web-scraper/note.txt", 8, 0, crc32(md), mdDef.length, md.length), mdDef);
        byte[] cd = bytes(rawCd("web-scraper/SKILL.md", 8, 0, crc32(md), mdDef.length, md.length, 0),
                rawCd("web-scraper/note.txt", 8, 0, crc32(md), mdDef.length, 1, first.length));
        assertCode(bytes(first, second, cd, rawEocd(2, cd.length, first.length + second.length)),
                "SKILL_ZIP_STRUCTURE_INVALID");
    }

    @Test
    @DisplayName("counter-control: an honest bit-3 package whose descriptor has no signature is accepted (#1242)")
    void rawHonestDescriptorWithoutSignatureAccepted() {
        // APPNOTE makes the 0x08074b50 signature marking a data descriptor
        // optional; both spellings occur in the wild and must stay accepted.
        byte[] md = SKILL_MD.getBytes(StandardCharsets.UTF_8);
        byte[] mdDef = deflate(md);
        byte[] bareDescriptor = bytes(le32(crc32(md)), le32(mdDef.length), le32(md.length));
        byte[] first = bytes(rawLocal("web-scraper/SKILL.md", 8, 8, 0, 0, 0), mdDef, bareDescriptor);
        byte[] cd = rawCd("web-scraper/SKILL.md", 8, 8, crc32(md), mdDef.length, md.length, 0);
        assertThat(SkillZipValidator.validate(bytes(first, cd, rawEocd(1, cd.length, first.length))).name())
                .isEqualTo("web-scraper");
    }

    @Test
    @DisplayName("fail-closed: a GBK (non-UTF-8) entry name is refused cleanly, not as a 500 (#1242 review round)")
    void nonUtf8EntryNameRejected() {
        // bsdtar on a zh-CN box writes entry names in the platform encoding with the
        // UTF-8 flag unset; the streaming reader's strict decode throws an
        // IllegalArgumentException where a malformed-package verdict belongs.
        // Pre-existing since develop (a 500 before #1242 too) — fixed alongside this
        // review round, not a regression of it.
        byte[] md = SKILL_MD.getBytes(StandardCharsets.UTF_8);
        byte[] mdDef = deflate(md);
        byte[] first = bytes(rawLocal("web-scraper/SKILL.md", 8, 0, crc32(md), mdDef.length, md.length), mdDef);
        // 笔=B1CA 记=BCC7 in GBK: not a valid UTF-8 sequence.
        byte[] gbkName = bytes("web-scraper/".getBytes(StandardCharsets.UTF_8),
                new byte[]{(byte) 0xB1, (byte) 0xCA, (byte) 0xBC, (byte) 0xC7, '.', 'm', 'd'});
        byte[] payload = "gbk named entry\n".getBytes(StandardCharsets.UTF_8);
        byte[] second = bytes(rawLocal(gbkName, 0, 0, crc32(payload), payload.length, payload.length), payload);
        byte[] cd = bytes(rawCd("web-scraper/SKILL.md", 8, 0, crc32(md), mdDef.length, md.length, 0),
                rawCd(gbkName, 0, 0, crc32(payload), payload.length, payload.length, first.length));

        assertCode(bytes(first, second, cd, rawEocd(2, cd.length, first.length + second.length)), "SKILL_ZIP_INVALID");
    }

    private static void assertStructureRejected(byte[] pkg, String what) {
        assertCode(pkg, "SKILL_ZIP_STRUCTURE_INVALID", what);
    }

    private static void assertCode(byte[] pkg, String expectedCode) {
        assertCode(pkg, expectedCode, expectedCode);
    }

    private static void assertCode(byte[] pkg, String expectedCode, String what) {
        assertThatThrownBy(() -> SkillZipValidator.validate(pkg)).as("%s must be rejected", what)
                .isInstanceOf(SkillValidationException.class)
                .satisfies(thrown -> assertThat(((SkillValidationException) thrown).code()).isEqualTo(expectedCode));
    }

    @Test
    @DisplayName("counter-control: an honest raw package (sizes upfront, DEFLATED) is accepted (#1242)")
    void rawHonestDeflateAccepted() {
        assertThat(SkillZipValidator.validate(rawHonestDeflate()).name()).isEqualTo("web-scraper");
    }

    @Test
    @DisplayName("counter-control: an honest raw package with STORED entries is accepted (#1242)")
    void rawHonestStoredAccepted() {
        assertThat(SkillZipValidator.validate(rawHonestStored()).name()).isEqualTo("web-scraper");
    }

    @Test
    @DisplayName("counter-control: honest directory entry + EOCD comment is accepted (#1242)")
    void rawHonestDirectoryAndCommentAccepted() {
        assertThat(SkillZipValidator.validate(rawHonestDirComment()).name()).isEqualTo("web-scraper");
    }

    // --- raw zip construction (#1242): full control over both views ---

    /** 640 MiB of zeros, raw-deflated; ~650 KB on the wire. Built once. */
    private static final class Payload {
        static final int SIZE = 640 * 1024 * 1024;
        static final byte[] DEFLATE;
        static final long CRC;

        static {
            try {
                Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
                try {
                    CRC32 crc = new CRC32();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] chunk = new byte[1024 * 1024];
                    byte[] buf = new byte[64 * 1024];
                    for (int i = 0; i < SIZE / chunk.length; i++) {
                        crc.update(chunk);
                        deflater.setInput(chunk);
                        while (!deflater.needsInput()) {
                            int n = deflater.deflate(buf);
                            if (n > 0) {
                                out.write(buf, 0, n);
                            }
                        }
                    }
                    deflater.finish();
                    while (!deflater.finished()) {
                        int n = deflater.deflate(buf);
                        if (n > 0) {
                            out.write(buf, 0, n);
                        }
                    }
                    DEFLATE = out.toByteArray();
                    CRC = crc.getValue();
                } finally {
                    deflater.end();
                }
            } catch (Exception e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    private static byte[] formA() {
        byte[] md = SKILL_MD.getBytes(StandardCharsets.UTF_8);
        byte[] mdDef = deflate(md);
        byte[] skillEntry = bytes(rawLocal("web-scraper/SKILL.md", 8, 0, crc32(md), mdDef.length, md.length), mdDef);
        byte[] payloadData = Payload.DEFLATE;
        // Local view: STORED, self-consistent, CRC over the raw (compressed) bytes.
        byte[] payloadEntry = bytes(
                rawLocal("web-scraper/payload.bin", 0, 0, crc32(payloadData), payloadData.length, payloadData.length),
                payloadData);
        // CD view: DEFLATED, inflating to 640 MiB.
        byte[] cd = bytes(rawCd("web-scraper/SKILL.md", 8, 0, crc32(md), mdDef.length, md.length, 0), rawCd(
                "web-scraper/payload.bin", 8, 0, Payload.CRC, payloadData.length, Payload.SIZE, skillEntry.length));
        return bytes(skillEntry, payloadEntry, cd, rawEocd(2, cd.length, skillEntry.length + payloadEntry.length));
    }

    private static byte[] formB() {
        byte[] md = SKILL_MD.getBytes(StandardCharsets.UTF_8);
        byte[] mdDef = deflate(md);
        byte[] skillEntry = bytes(rawLocal("web-scraper/SKILL.md", 8, 0, crc32(md), mdDef.length, md.length), mdDef);
        String carrierName = "web-scraper/asset.bin";
        byte[] fakeLocal = rawLocal("web-scraper/payload.bin", 8, 0, Payload.CRC, Payload.DEFLATE.length, Payload.SIZE);
        byte[] carrierData = bytes(fakeLocal, Payload.DEFLATE);
        byte[] carrierEntry = bytes(
                rawLocal(carrierName, 0, 0, crc32(carrierData), carrierData.length, carrierData.length), carrierData);
        long fakeOffset = skillEntry.length + rawLocal(carrierName, 0, 0, 0, 0, 0).length;
        byte[] cd = bytes(rawCd("web-scraper/SKILL.md", 8, 0, crc32(md), mdDef.length, md.length, 0),
                rawCd("web-scraper/payload.bin", 8, 0, Payload.CRC, Payload.DEFLATE.length, Payload.SIZE, fakeOffset));
        return bytes(skillEntry, carrierEntry, cd, rawEocd(2, cd.length, skillEntry.length + carrierEntry.length));
    }

    private static byte[] formC() {
        byte[] md = SKILL_MD.getBytes(StandardCharsets.UTF_8);
        byte[] mdDef = deflate(md);
        byte[] skillEntry = bytes(rawLocal("web-scraper/SKILL.md", 8, 0, crc32(md), mdDef.length, md.length), mdDef);
        String name = "web-scraper/data.bin";
        byte[] fakeLocal = rawLocal(name, 8, 0, Payload.CRC, Payload.DEFLATE.length, Payload.SIZE);
        byte[] carrierData = bytes(fakeLocal, Payload.DEFLATE);
        byte[] carrierEntry = bytes(rawLocal(name, 0, 0, crc32(carrierData), carrierData.length, carrierData.length),
                carrierData);
        long fakePos = skillEntry.length + rawLocal(name, 0, 0, 0, 0, 0).length;
        byte[] cdHonest = bytes(rawCd("web-scraper/SKILL.md", 8, 0, crc32(md), mdDef.length, md.length, 0),
                rawCd(name, 0, 0, crc32(carrierData), carrierData.length, carrierData.length, skillEntry.length));
        // python's zipfile reads the CD at cdOffset + concat and adds concat to every
        // header_offset (concat = EOCD_pos - cdSize - cdOffset); with two equal-length
        // CDs that lands on the evil one, so its offset is pre-subtracted.
        byte[] cdEvil = bytes(rawCd("web-scraper/SKILL.md", 8, 0, crc32(md), mdDef.length, md.length, 0),
                rawCd(name, 8, 0, Payload.CRC, Payload.DEFLATE.length, Payload.SIZE, fakePos - cdHonest.length));
        if (cdHonest.length != cdEvil.length) {
            throw new IllegalStateException("form C needs equal-length central directories");
        }
        return bytes(skillEntry, carrierEntry, cdHonest, cdEvil,
                rawEocd(2, cdHonest.length, skillEntry.length + carrierEntry.length));
    }

    private static byte[] rawHonestDeflate() {
        byte[] md = SKILL_MD.getBytes(StandardCharsets.UTF_8);
        byte[] mdDef = deflate(md);
        byte[] asset = "hello asset\n".repeat(100).getBytes(StandardCharsets.UTF_8);
        byte[] assetDef = deflate(asset);
        byte[] first = bytes(rawLocal("web-scraper/SKILL.md", 8, 0, crc32(md), mdDef.length, md.length), mdDef);
        byte[] second = bytes(
                rawLocal("web-scraper/assets/note.txt", 8, 0, crc32(asset), assetDef.length, asset.length), assetDef);
        byte[] cd = bytes(rawCd("web-scraper/SKILL.md", 8, 0, crc32(md), mdDef.length, md.length, 0),
                rawCd("web-scraper/assets/note.txt", 8, 0, crc32(asset), assetDef.length, asset.length, first.length));
        return bytes(first, second, cd, rawEocd(2, cd.length, first.length + second.length));
    }

    private static byte[] rawHonestStored() {
        byte[] md = SKILL_MD.getBytes(StandardCharsets.UTF_8);
        byte[] asset = "hello asset\n".repeat(100).getBytes(StandardCharsets.UTF_8);
        byte[] first = bytes(rawLocal("web-scraper/SKILL.md", 0, 0, crc32(md), md.length, md.length), md);
        byte[] second = bytes(rawLocal("web-scraper/assets/note.txt", 0, 0, crc32(asset), asset.length, asset.length),
                asset);
        byte[] cd = bytes(rawCd("web-scraper/SKILL.md", 0, 0, crc32(md), md.length, md.length, 0),
                rawCd("web-scraper/assets/note.txt", 0, 0, crc32(asset), asset.length, asset.length, first.length));
        return bytes(first, second, cd, rawEocd(2, cd.length, first.length + second.length));
    }

    private static byte[] rawHonestDirComment() {
        byte[] md = SKILL_MD.getBytes(StandardCharsets.UTF_8);
        byte[] mdDef = deflate(md);
        byte[] first = bytes(rawLocal("web-scraper/SKILL.md", 8, 0, crc32(md), mdDef.length, md.length), mdDef);
        byte[] dir = rawLocal("web-scraper/assets/", 0, 0, 0, 0, 0);
        byte[] cd = bytes(rawCd("web-scraper/SKILL.md", 8, 0, crc32(md), mdDef.length, md.length, 0),
                rawCd("web-scraper/assets/", 0, 0, 0, 0, 0, first.length));
        return bytes(first, dir, cd,
                rawEocd(2, cd.length, first.length + dir.length, "skill package".getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] rawLocal(String name, int method, int flags, long crc, long csize, long usize) {
        return rawLocal(name.getBytes(StandardCharsets.UTF_8), method, flags, crc, csize, usize);
    }

    private static byte[] rawLocal(byte[] name, int method, int flags, long crc, long csize, long usize) {
        return bytes(le32(0x04034B50L), le16(20), le16(flags), le16(method), le16(0), le16(0), le32(crc), le32(csize),
                le32(usize), le16(name.length), le16(0), name);
    }

    private static byte[] rawDescriptor(long crc, long csize, long usize) {
        return bytes(le32(0x08074B50L), le32(crc), le32(csize), le32(usize));
    }

    private static byte[] rawCd(String name, int method, int flags, long crc, long csize, long usize, long offset) {
        return rawCd(name.getBytes(StandardCharsets.UTF_8), method, flags, crc, csize, usize, offset);
    }

    private static byte[] rawCd(byte[] name, int method, int flags, long crc, long csize, long usize, long offset) {
        return bytes(le32(0x02014B50L), le16(20), le16(20), le16(flags), le16(method), le16(0), le16(0), le32(crc),
                le32(csize), le32(usize), le16(name.length), le16(0), le16(0), le16(0), le16(0), le32(0), le32(offset),
                name);
    }

    private static byte[] rawEocd(int count, long cdSize, long cdOffset) {
        return rawEocd(count, cdSize, cdOffset, new byte[0]);
    }

    private static byte[] rawEocd(int count, long cdSize, long cdOffset, byte[] comment) {
        return bytes(le32(0x06054B50L), le16(0), le16(0), le16(count), le16(count), le32(cdSize), le32(cdOffset),
                le16(comment.length), comment);
    }

    private static byte[] le16(int value) {
        return new byte[]{(byte) value, (byte) (value >> 8)};
    }

    private static byte[] le32(long value) {
        return new byte[]{(byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)};
    }

    private static byte[] bytes(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        try {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            while (!deflater.finished()) {
                int n = deflater.deflate(buf);
                if (n > 0) {
                    out.write(buf, 0, n);
                }
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
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
