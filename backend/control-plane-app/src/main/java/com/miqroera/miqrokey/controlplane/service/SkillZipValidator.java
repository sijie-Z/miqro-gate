package com.miqroera.miqrokey.controlplane.service;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Validates SkillHub uploads against the Anthropic Agent Skills format (P2.2):
 * the zip must hold exactly one skill directory whose name matches the
 * {@code SKILL.md} frontmatter {@code name} (kebab-case), with a non-blank
 * {@code description}. Optional frontmatter fields ({@code author},
 * {@code license}, {@code tags}) become catalog metadata. Entry names are
 * normalized and must stay inside that directory: {@code ..} segments, absolute
 * paths and drive letters are rejected (#1233, zip-slip). Bounds guard
 * oversized packages and zip bombs — nothing is ever extracted to disk;
 * SKILL.md is the only content read into memory, the rest is inflated and
 * discarded to charge the decompressed-volume cap. Since #1242 the local-header
 * stream is cross-checked against the central directory first (see
 * {@link #verifyTwoViews}): the charged volume always matches what mainstream
 * extractors — which read the central directory — will produce, and any package
 * whose two views disagree is refused fail-closed.
 */
public final class SkillZipValidator {

    /** Upper bound for an uploaded skill package. */
    public static final int MAX_ZIP_BYTES = 5 * 1024 * 1024;
    /** Upper bound for the SKILL.md file we read for metadata. */
    public static final int MAX_SKILL_MD_BYTES = 512 * 1024;
    /** Upper bound for zip entries (bomb guard). */
    public static final int MAX_ENTRIES = 200;
    /**
     * Upper bound for the total decompressed volume of an uploaded package (bomb
     * guard, #1233): a 638 KB package can hold ~640 MB of deflated data, which
     * downstream users would write to disk in full when extracting.
     */
    public static final long MAX_TOTAL_DECOMPRESSED_BYTES = 64L * 1024 * 1024;
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
        // #1242: the stream walk below is only the validator's own view of the
        // package. Verify it against the central directory — what ZipFile,
        // python's zipfile, .NET and PowerShell extractors follow — before
        // anything is charged, so the caps always cover what will be extracted.
        verifyTwoViews(zip);
        String rootDir = null;
        String skillMdText = null;
        int entries = 0;
        long decompressedBytes = 0;
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
                    decompressedBytes += skillMd.length;
                } else {
                    // #1233: a declared size is never trusted for the cap — it is -1
                    // on streamed (data-descriptor) zips, which must not read as "0",
                    // and a crafted zip can declare any small number over a deflate
                    // stream that inflates to gigabytes. The declared value may only
                    // fail fast; the cap is charged with the bytes actually inflated.
                    if (entry.getSize() > MAX_TOTAL_DECOMPRESSED_BYTES - decompressedBytes) {
                        throw decompressedTooLarge();
                    }
                    decompressedBytes = countDecompressedBytes(zis, decompressedBytes);
                }
                if (decompressedBytes > MAX_TOTAL_DECOMPRESSED_BYTES) {
                    throw decompressedTooLarge();
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
     * {@code ..} segment — however the separator is spelled, since extractors on
     * Windows split on '\' too. Empty and '.' segments are dropped; nothing else is
     * rewritten, so accepted entries reach extractors unchanged.
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
        return invalid("SKILL_ENTRY_PATH_INVALID", "技能包内存在不安全的条目路径：条目名不允许 .. 段、绝对路径，或规范化后越出技能根目录。");
    }

    /**
     * Reads the current entry through the inflater and returns the new running
     * total of decompressed bytes, rejecting as soon as the cap is passed. The true
     * size comes from the inflater, never from the declared
     * {@link ZipEntry#getSize()}: counting costs nothing extra here because
     * skipping ahead to the next entry already inflates this same data.
     */
    private static long countDecompressedBytes(ZipInputStream zis, long alreadyCounted) throws java.io.IOException {
        byte[] buffer = new byte[8192];
        long total = alreadyCounted;
        int read;
        while ((read = zis.read(buffer)) != -1) {
            total += read;
            if (total > MAX_TOTAL_DECOMPRESSED_BYTES) {
                throw decompressedTooLarge();
            }
        }
        return total;
    }

    private static SkillValidationException decompressedTooLarge() {
        return invalid("SKILL_DECOMPRESSED_TOO_LARGE",
                "技能包解压后总体积超过 %d MB 上限。".formatted(MAX_TOTAL_DECOMPRESSED_BYTES / 1024 / 1024));
    }

    // ------------------------------------------------------------------
    // #1242: two-view structure verification
    //
    // A zip can be read through two views that need not agree: the sequential
    // local-header chain (what ZipInputStream walks, and what the caps below
    // are charged through) and the central directory (what java.util.zip
    // .ZipFile, python's zipfile, .NET and PowerShell follow when extracting).
    // A crafted package whose central directory describes different data than
    // its local headers lets the validator see a small package while an
    // extractor inflates hundreds of megabytes. The checks below accept a
    // package only when the two views describe the same entries — same count,
    // names, flags, methods, sizes, CRCs and file offsets — and the file is
    // exactly tiled by [local chain][central directory][EOCD(+comment)] with no
    // unaccounted bytes; everything else is refused fail-closed.
    // ------------------------------------------------------------------

    private static final long LOC_SIG = 0x04034B50L;
    private static final long CEN_SIG = 0x02014B50L;
    private static final long EOCD_SIG = 0x06054B50L;
    private static final long EXTSIG = 0x08074B50L;
    private static final long ZIP64_EOCD_LOCATOR_SIG = 0x07064B50L;
    private static final int ZIP64_EXTRA_ID = 0x0001;
    /**
     * Entry flag bits that change how an entry is interpreted. Bits 1–2 (the
     * deflate compression-level hints) are excluded: they are pure hints whose
     * spelling in the two views may differ without changing the data.
     */
    private static final int SEMANTIC_FLAGS = 0xFFF9;

    /** A central-directory record, as the directory-based readers see it. */
    private record CdEntry(byte[] name, int flags, int method, long crc, long csize, long usize, long offset) {
    }

    /** The actual span of a deflated entry, measured by inflating it. */
    private record Inflated(long csize, long usize, long crc, long end) {
    }

    private static void verifyTwoViews(byte[] zip) {
        int eocd = findEocd(zip);
        if (eocd < 0) {
            throw zipInvalid();
        }
        int diskNumber = u16(zip, eocd + 4);
        int cdStartDisk = u16(zip, eocd + 6);
        int entriesThisDisk = u16(zip, eocd + 8);
        int totalEntries = u16(zip, eocd + 10);
        long cdSize = u32(zip, eocd + 12);
        long cdOffset = u32(zip, eocd + 16);
        // zip64 markers: sentinel field values, or the zip64 EOCD locator that
        // a zip64 archive carries right before the EOCD record.
        boolean zip64 = entriesThisDisk == 0xFFFF || totalEntries == 0xFFFF || cdSize == 0xFFFFFFFFL
                || cdOffset == 0xFFFFFFFFL || (eocd >= 20 && u32(zip, eocd - 20) == ZIP64_EOCD_LOCATOR_SIG);
        if (zip64) {
            throw invalid("SKILL_ZIP64_UNSUPPORTED", "技能包使用了不支持的 zip64 结构。");
        }
        if (diskNumber != 0 || cdStartDisk != 0 || entriesThisDisk != totalEntries) {
            throw structureInvalid();
        }
        if (totalEntries > MAX_ENTRIES) {
            throw invalid("SKILL_TOO_MANY_ENTRIES", "技能包条目数超过上限。");
        }
        if (cdOffset + cdSize != eocd) {
            // The central directory must abut the EOCD record: a gap (a second,
            // extractor-visible directory, as in #1242 form C, or concatenated
            // data) or an overlap makes the layout ambiguous.
            throw structureInvalid();
        }
        for (int at = eocd + 1; at + 4 <= zip.length; at++) {
            // Bytes after the chosen record may only be padding (the block fill a
            // streaming archiver writes): a further EOCD signature would let some
            // reader pick a different record than this validator read.
            if (u32(zip, at) == EOCD_SIG) {
                throw structureInvalid();
            }
        }
        CdEntry[] cd = parseCentralDirectory(zip, eocd, totalEntries, cdOffset, cdSize);
        int at = 0;
        long decompressed = 0;
        for (int i = 0; i < totalEntries; i++) {
            if (at + 30 > cdOffset) {
                throw structureInvalid();
            }
            if (u32(zip, at) != LOC_SIG) {
                throw i == 0 ? zipInvalid() : structureInvalid();
            }
            int flags = u16(zip, at + 6);
            int method = u16(zip, at + 8);
            long declaredCrc = u32(zip, at + 14);
            long declaredCsize = u32(zip, at + 18);
            long declaredUsize = u32(zip, at + 22);
            int nameLen = u16(zip, at + 26);
            int extraLen = u16(zip, at + 28);
            long dataStart = at + 30L + nameLen + extraLen;
            if (dataStart > cdOffset) {
                throw structureInvalid();
            }
            byte[] name = Arrays.copyOfRange(zip, at + 30, at + 30 + nameLen);
            requireNoZip64Extra(zip, at + 30 + nameLen, extraLen);
            if ((flags & 1) != 0) {
                throw zipInvalid(); // encrypted entries: the streaming reader refuses them too
            }
            long csize;
            long usize;
            long crc;
            long next;
            if (method == 0) {
                if ((flags & 8) != 0) {
                    // Where a stored entry's data ends is knowable only from the
                    // very sizes the descriptor is supposed to verify — nothing
                    // about this entry can be cross-checked.
                    throw structureInvalid();
                }
                if (declaredCsize != declaredUsize || dataStart + declaredCsize > cdOffset) {
                    throw structureInvalid();
                }
                csize = declaredCsize;
                usize = declaredUsize;
                crc = crc32(zip, (int) dataStart, (int) declaredCsize);
                if (crc != declaredCrc) {
                    throw structureInvalid();
                }
                next = dataStart + declaredCsize;
            } else if (method == 8) {
                boolean viaDescriptor = (flags & 8) != 0;
                if (!viaDescriptor && dataStart + declaredCsize > cdOffset) {
                    // The declared compressed span must fit before the directory.
                    // Feeding an over-long declared size to the inflater verbatim
                    // read past the end of the array and leaked an uncaught
                    // ArrayIndexOutOfBoundsException (500 instead of a clean 400).
                    throw structureInvalid();
                }
                Inflated inflated = inflateEntry(zip, dataStart,
                        Math.min(viaDescriptor ? cdOffset - dataStart : declaredCsize, zip.length - dataStart),
                        decompressed);
                if (viaDescriptor) {
                    // #1242 direction 2: the descriptor is what a streaming
                    // reader uses for sizes; it must match the bytes actually
                    // inflated (and so take part in the directory comparison).
                    next = readDescriptor(zip, inflated.end(), inflated, cdOffset);
                } else {
                    if (inflated.csize() != declaredCsize || inflated.usize() != declaredUsize
                            || inflated.crc() != declaredCrc) {
                        throw structureInvalid();
                    }
                    next = dataStart + declaredCsize;
                }
                csize = inflated.csize();
                usize = inflated.usize();
                crc = inflated.crc();
            } else {
                throw zipInvalid(); // unsupported compression method, refused as before
            }
            decompressed += usize;
            if (decompressed > MAX_TOTAL_DECOMPRESSED_BYTES) {
                throw decompressedTooLarge();
            }
            CdEntry entry = cd[i];
            if (!Arrays.equals(name, entry.name()) || (flags & SEMANTIC_FLAGS) != (entry.flags() & SEMANTIC_FLAGS)
                    || method != entry.method() || at != entry.offset() || csize != entry.csize()
                    || usize != entry.usize() || crc != entry.crc()) {
                throw structureInvalid();
            }
            at = (int) next;
        }
        if (at != cdOffset) {
            // Bytes between the last local entry and the central directory are
            // attributable to neither view.
            throw structureInvalid();
        }
    }

    private static CdEntry[] parseCentralDirectory(byte[] zip, int eocd, int totalEntries, long cdOffset, long cdSize) {
        CdEntry[] entries = new CdEntry[totalEntries];
        int at = (int) cdOffset;
        for (int i = 0; i < totalEntries; i++) {
            if (at + 46 > eocd || u32(zip, at) != CEN_SIG) {
                throw structureInvalid();
            }
            int flags = u16(zip, at + 8);
            int method = u16(zip, at + 10);
            long crc = u32(zip, at + 16);
            long csize = u32(zip, at + 20);
            long usize = u32(zip, at + 24);
            int nameLen = u16(zip, at + 28);
            int extraLen = u16(zip, at + 30);
            int commentLen = u16(zip, at + 32);
            int diskStart = u16(zip, at + 34);
            long offset = u32(zip, at + 42);
            if (diskStart != 0 || at + 46L + nameLen + extraLen + commentLen > eocd) {
                throw structureInvalid();
            }
            byte[] name = Arrays.copyOfRange(zip, at + 46, at + 46 + nameLen);
            requireNoZip64Extra(zip, at + 46 + nameLen, extraLen);
            entries[i] = new CdEntry(name, flags, method, crc, csize, usize, offset);
            at += 46 + nameLen + extraLen + commentLen;
        }
        if (at != cdOffset + cdSize) {
            throw structureInvalid();
        }
        return entries;
    }

    /**
     * Inflates one deflated entry within {@code inputBound} bytes and returns the
     * compressed span the stream actually consumed, its actual inflated size and
     * CRC — all measured from the data, never taken from declared header fields.
     */
    private static Inflated inflateEntry(byte[] zip, long dataStart, long inputBound, long alreadyCounted) {
        Inflater inflater = new Inflater(true);
        try {
            CRC32 crc = new CRC32();
            byte[] out = new byte[8192];
            long pushed = 0;
            long total = 0;
            int at = (int) dataStart;
            long available = inputBound;
            while (!inflater.finished()) {
                if (inflater.needsInput()) {
                    if (available <= 0) {
                        throw structureInvalid(); // the deflate stream runs past its declared span
                    }
                    // Never hand the inflater a slice past the end of the array,
                    // whatever bound the caller computed.
                    int n = (int) Math.min(Math.min(available, 1 << 20), zip.length - (long) at);
                    if (n <= 0) {
                        throw structureInvalid();
                    }
                    inflater.setInput(zip, at, n);
                    at += n;
                    pushed += n;
                    available -= n;
                }
                int n = inflater.inflate(out);
                if (n > 0) {
                    crc.update(out, 0, n);
                    total += n;
                    if (alreadyCounted + total > MAX_TOTAL_DECOMPRESSED_BYTES) {
                        throw decompressedTooLarge();
                    }
                } else if (!inflater.finished()) {
                    if (inflater.needsDictionary() || !inflater.needsInput()) {
                        throw structureInvalid(); // malformed deflate data: no forward progress
                    }
                }
            }
            long consumed = pushed - inflater.getRemaining();
            return new Inflated(consumed, total, crc.getValue(), dataStart + consumed);
        } catch (DataFormatException e) {
            // Same verdict a streaming reader reaches for corrupt deflate data.
            throw zipInvalid();
        } finally {
            inflater.end();
        }
    }

    /**
     * Reads the data descriptor after a bit-3 entry's deflated data and requires it
     * to describe the bytes that were actually inflated (the conventional
     * 0x08074b50 signature is optional per APPNOTE; both spellings are read).
     */
    private static long readDescriptor(byte[] zip, long at, Inflated inflated, long limit) {
        if (at + 12 > limit) {
            throw structureInvalid();
        }
        long crc = u32(zip, (int) at);
        long csize = u32(zip, (int) at + 4);
        long usize = u32(zip, (int) at + 8);
        long end = at + 12;
        if (crc == EXTSIG) {
            if (at + 16 > limit) {
                throw structureInvalid();
            }
            crc = u32(zip, (int) at + 4);
            csize = u32(zip, (int) at + 8);
            usize = u32(zip, (int) at + 12);
            end = at + 16;
        }
        if (crc != inflated.crc() || csize != inflated.csize() || usize != inflated.usize()) {
            throw structureInvalid();
        }
        return end;
    }

    /**
     * Locates the end-of-central-directory record the way the directory-based
     * readers do: the last occurrence of the signature in the trailing window
     * (the last 64 KiB + 22 bytes, matching python's rfind, Java's backward scan
     * and .NET's SeekBackwardsToSignature). Bytes after the record — block
     * padding written by streaming archivers such as bsdtar/libarchive — are
     * tolerated; they must not carry a second EOCD signature (enforced by the
     * uniqueness scan in {@link #verifyTwoViews}) and the record must still abut
     * the central directory.
     */
    private static int findEocd(byte[] zip) {
        if (zip.length < 22) {
            return -1;
        }
        for (int at = zip.length - 22; at >= Math.max(0, zip.length - 22 - 0xFFFF); at--) {
            if (u32(zip, at) == EOCD_SIG) {
                return at;
            }
        }
        return -1;
    }

    /**
     * Scans an extra-field block and refuses zip64 markers: they carry alternative
     * sizes that some readers honour, which is precisely the kind of second
     * description this validator must not accept unverified. A malformed block is
     * refused as well.
     */
    private static void requireNoZip64Extra(byte[] zip, int at, int length) {
        int end = at + length;
        if (end > zip.length) {
            throw structureInvalid();
        }
        int pos = at;
        while (pos < end) {
            if (pos + 4 > end) {
                throw structureInvalid();
            }
            int id = u16(zip, pos);
            int size = u16(zip, pos + 2);
            if (pos + 4 + size > end) {
                throw structureInvalid();
            }
            if (id == ZIP64_EXTRA_ID) {
                throw invalid("SKILL_ZIP64_UNSUPPORTED", "技能包使用了不支持的 zip64 结构。");
            }
            pos += 4 + size;
        }
    }

    private static long crc32(byte[] zip, int at, int length) {
        CRC32 crc = new CRC32();
        crc.update(zip, at, length);
        return crc.getValue();
    }

    private static int u16(byte[] zip, int at) {
        return (zip[at] & 0xFF) | ((zip[at + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] zip, int at) {
        return (zip[at] & 0xFFL) | ((zip[at + 1] & 0xFFL) << 8) | ((zip[at + 2] & 0xFFL) << 16)
                | ((zip[at + 3] & 0xFFL) << 24);
    }

    private static SkillValidationException zipInvalid() {
        return invalid("SKILL_ZIP_INVALID", "技能包不是有效的 zip 文件。");
    }

    private static SkillValidationException structureInvalid() {
        return invalid("SKILL_ZIP_STRUCTURE_INVALID", "技能包内部结构不一致（条目流与中央目录无法相互核对）。");
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
