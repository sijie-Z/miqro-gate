package com.miqroera.miqrokey.controlplane.service;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

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
        // #1242: the walk below is only the validator's own view of the package.
        // Verify it against the central directory — what ZipFile, python's
        // zipfile, .NET and PowerShell extractors follow — before anything is
        // charged, so the caps always cover what will be extracted. The walk then
        // runs on the verified entries themselves, never on a
        // java.util.zip.ZipInputStream: that reader misparses the unsigned
        // data-descriptor spelling this class accepts (a CRC32 equal to the
        // signature value, review r2 P2a), and nothing here may hinge on a reader
        // quirk the two-view check has already ruled out.
        List<VerifiedEntry> verified = verifyTwoViews(zip);
        String rootDir = null;
        String skillMdText = null;
        long decompressedBytes = 0;
        Set<String> deliveredPaths = new HashSet<>();
        for (VerifiedEntry entry : verified) {
            String path = normalizedEntryPath(strictUtf8Name(entry.name()));
            if (!deliveredPaths.add(path)) {
                // Two entries normalizing to one delivered path (#1242 r2
                // adversarial round): extractors disagree on which duplicate wins
                // (python and java.util.zip.ZipFile take the directory-order-last,
                // .NET the first, and extraction overwrites in its own order), so
                // the bytes a user unpacks can differ from the entry this walk
                // parsed and charged. No ordering rule can restore agreement —
                // refused fail-closed whatever the order.
                throw structureInvalid();
            }
            int slash = path.indexOf('/');
            String top = slash > 0 ? path.substring(0, slash) : path;
            if (rootDir == null) {
                rootDir = top;
            } else if (!top.equals(rootDir)) {
                throw invalid("SKILL_MULTIPLE_ROOTS", "技能包必须只包含一个技能目录（根目录下不能有多余文件）。");
            }
            if (!entry.isDirectory() && path.equals(rootDir + "/SKILL.md")) {
                // #427: the two-view check measured the entry by inflating it, so
                // usize is the actual decompressed size whatever the local header
                // declared (streamed zips carry 0/-1 there) — both bounds below
                // apply to the data itself.
                if (entry.usize() > MAX_SKILL_MD_BYTES) {
                    throw invalid("SKILL_MD_TOO_LARGE", "SKILL.md 超过大小上限。");
                }
                byte[] skillMd = readEntryBytes(zip, entry);
                if (skillMd.length > MAX_SKILL_MD_BYTES) {
                    throw invalid("SKILL_MD_TOO_LARGE", "SKILL.md 超过大小上限。");
                }
                skillMdText = new String(skillMd, StandardCharsets.UTF_8);
                decompressedBytes += skillMd.length;
            } else {
                // #1233: the cap is charged with the measured decompressed size of
                // the entry — verifyTwoViews inflated this very data — never with a
                // declared size (streamed zips declare -1, crafted ones lie).
                if (entry.usize() > MAX_TOTAL_DECOMPRESSED_BYTES - decompressedBytes) {
                    throw decompressedTooLarge();
                }
                decompressedBytes += entry.usize();
            }
            if (decompressedBytes > MAX_TOTAL_DECOMPRESSED_BYTES) {
                throw decompressedTooLarge();
            }
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
     * One entry as verified by {@link #verifyTwoViews}: both views agree on the
     * name, compression method and sizes, and for deflated entries the sizes were
     * measured by inflating the data. The walk in {@link #validate} is driven by
     * these entries.
     */
    private record VerifiedEntry(byte[] name, int method, long csize, long usize, long dataStart) {
        boolean isDirectory() {
            return name.length > 0 && name[name.length - 1] == '/';
        }
    }

    /**
     * Decodes an entry-name byte string the way the streaming reader did: strict
     * UTF-8, malformed input refused. A name in another encoding (GBK, as bsdtar on
     * a zh-CN box writes them) becomes a clean invalid-package verdict instead of
     * the stream reader's uncaught IllegalArgumentException (#1242 review round).
     */
    private static String strictUtf8Name(byte[] name) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(name)).toString();
        } catch (CharacterCodingException e) {
            throw zipInvalid();
        }
    }

    /**
     * Materializes one verified entry's bytes — only ever called for SKILL.md,
     * whose size is bounded by {@link #MAX_SKILL_MD_BYTES} one check above: stored
     * data is copied straight out of the package, deflated data inflated over the
     * span the verification measured.
     */
    private static byte[] readEntryBytes(byte[] zip, VerifiedEntry entry) {
        if (entry.method() == 0) {
            return Arrays.copyOfRange(zip, (int) entry.dataStart(), (int) (entry.dataStart() + entry.csize()));
        }
        Inflater inflater = new Inflater(true);
        try {
            byte[] out = new byte[(int) entry.usize()];
            inflater.setInput(zip, (int) entry.dataStart(), (int) entry.csize());
            int filled = 0;
            while (filled < out.length) {
                int n = inflater.inflate(out, filled, out.length - filled);
                if (n > 0) {
                    filled += n;
                } else if (inflater.finished()) {
                    break; // the data ended short of the measured size: refused below
                } else {
                    throw structureInvalid(); // malformed deflate data: no forward progress
                }
            }
            if (filled != out.length) {
                // Cannot happen for an entry verifyTwoViews measured; kept as an
                // explicit fail-closed assertion.
                throw structureInvalid();
            }
            return out;
        } catch (DataFormatException e) {
            throw zipInvalid();
        } finally {
            inflater.end();
        }
    }

    private static SkillValidationException decompressedTooLarge() {
        return invalid("SKILL_DECOMPRESSED_TOO_LARGE",
                "技能包解压后总体积超过 %d MB 上限。".formatted(MAX_TOTAL_DECOMPRESSED_BYTES / 1024 / 1024));
    }

    // ------------------------------------------------------------------
    // #1242: two-view structure verification
    //
    // A zip can be read through two views that need not agree: the sequential
    // local-header chain (what streaming readers walk, and what the walk in
    // validate() is driven by) and the central directory (what java.util.zip
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
     * Maximum EOCD comment length: the record's own 16-bit comment-length field
     * (APPNOTE 4.3.16). A comment is arbitrary data in which the EOCD signature
     * bytes may legally appear, so the bytes it declares are exempt from the
     * second-EOCD scan.
     */
    private static final int MAX_EOCD_COMMENT = 0xFFFF;
    /**
     * Padding allowance in the EOCD search window behind a full comment
     * ({@link #findEocd} reaches back 22 + a whole comment
     * ({@link #MAX_EOCD_COMMENT}) + this much padding). Not an enforced per-file
     * bound: with a shorter comment the tolerated padding grows by the unused
     * comment allowance. The fill is the block padding streaming archivers write
     * (bsdtar/libarchive style).
     */
    private static final int MAX_TRAILING_PADDING = 0xFFFF;
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

    private static List<VerifiedEntry> verifyTwoViews(byte[] zip) {
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
        // The chosen record's comment is the commentLength bytes that follow it;
        // bytes past that are padding (the block fill a streaming archiver
        // writes). A comment is arbitrary declared data and may contain the EOCD
        // signature bytes — that is not a second record — so the scan starts
        // after the comment (review r2, P2b: it used to start right after the
        // record and refused a signature inside a declared comment, which java
        // .util.zip.ZipFile and .NET both read). The padding must stay free of a
        // further EOCD signature: one there could let a reader (python's rfind,
        // .NET's backward scan) pick a different record than this validator read,
        // so the whole padding region is still scanned.
        int commentLength = u16(zip, eocd + 20);
        int eocdEnd = eocd + 22 + commentLength;
        if (eocdEnd > zip.length) {
            throw structureInvalid(); // a comment cannot extend past the file
        }
        for (int at = eocdEnd; at + 4 <= zip.length; at++) {
            if (u32(zip, at) == EOCD_SIG) {
                throw structureInvalid();
            }
        }
        CdEntry[] cd = parseCentralDirectory(zip, eocd, totalEntries, cdOffset, cdSize);
        // Pair each local header with the directory record that points at it
        // ("relative offset of local header"), not with the record at the same
        // index (review r2, P1): the zip spec does not require the two lists in
        // the same order, and every extractor (python's zipfile,
        // java.util.zip.ZipFile, .NET) resolves entries through the offset. The
        // pairing stays a verified bijection — each offset must name exactly one
        // record and every record must be reached exactly once — so a directory
        // whose order differs is accepted only while the offsets and every
        // compared field still agree.
        Map<Long, CdEntry> cdByOffset = new HashMap<>();
        for (CdEntry record : cd) {
            if (cdByOffset.put(record.offset(), record) != null) {
                throw structureInvalid(); // two records claiming one local header
            }
        }
        List<VerifiedEntry> verified = new ArrayList<>();
        int at = 0;
        long decompressed = 0;
        int matched = 0;
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
            CdEntry entry = cdByOffset.get((long) at);
            if (entry == null) {
                throw structureInvalid(); // a local header no directory record points at
            }
            matched++;
            if (!Arrays.equals(name, entry.name()) || (flags & SEMANTIC_FLAGS) != (entry.flags() & SEMANTIC_FLAGS)
                    || method != entry.method() || csize != entry.csize() || usize != entry.usize()
                    || crc != entry.crc()) {
                throw structureInvalid();
            }
            verified.add(new VerifiedEntry(name, method, csize, usize, dataStart));
            at = (int) next;
        }
        if (matched != totalEntries) {
            // Bijection invariant, kept as an explicit fail-closed assertion: the
            // duplicate-offset rejection above and the null lookup in the walk
            // already guarantee it, so this cannot fire unless the two fall out of
            // step (in which case silence would be a hole, not a false alarm).
            throw structureInvalid();
        }
        if (at != cdOffset) {
            // Bytes between the last local entry and the central directory are
            // attributable to neither view.
            throw structureInvalid();
        }
        return verified;
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
     * Reads the data descriptor after a bit-3 entry's deflated data. APPNOTE makes
     * the conventional 0x08074b50 signature optional, so both spellings occur in
     * the wild. The 16-byte signed form is adopted only when its three values match
     * the bytes actually inflated; the 12-byte unsigned form is tried otherwise,
     * and a descriptor that fits neither is refused. Checking the signed values
     * before adopting them is what keeps a legitimately unsigned descriptor
     * readable when the entry's CRC32 happens to equal the signature value
     * 0x08074b50 (review r2, P2a): the old code took the matching first word as
     * proof of the signature form and misread the whole descriptor.
     */
    private static long readDescriptor(byte[] zip, long at, Inflated inflated, long limit) {
        if (at + 16 <= limit && u32(zip, (int) at) == EXTSIG && u32(zip, (int) at + 4) == inflated.crc()
                && u32(zip, (int) at + 8) == inflated.csize() && u32(zip, (int) at + 12) == inflated.usize()) {
            return at + 16;
        }
        if (at + 12 <= limit && u32(zip, (int) at) == inflated.crc() && u32(zip, (int) at + 4) == inflated.csize()
                && u32(zip, (int) at + 8) == inflated.usize()) {
            return at + 12;
        }
        throw structureInvalid();
    }

    /**
     * Locates the end-of-central-directory record the way the directory-based
     * readers do: the last occurrence of the signature in the trailing window. The
     * window covers the largest tolerated tail — a full comment
     * ({@link #MAX_EOCD_COMMENT}) plus the full trailing padding
     * ({@link #MAX_TRAILING_PADDING}) — or a package combining both would have its
     * record out of reach (review r2, P2b: .NET's backward scan reads such
     * packages; python's rfind and Java's comment-to-EOF scan stop at ~64 KiB, but
     * the tolerance rules of this validator must compose with its own search).
     * Bytes after the record — the comment and block padding written by streaming
     * archivers such as bsdtar/libarchive — are tolerated; they must not carry a
     * second EOCD record (enforced by the scan in {@link #verifyTwoViews}) and the
     * record must still abut the central directory.
     */
    private static int findEocd(byte[] zip) {
        if (zip.length < 22) {
            return -1;
        }
        int window = 22 + MAX_EOCD_COMMENT + MAX_TRAILING_PADDING;
        for (int at = zip.length - 22; at >= Math.max(0, zip.length - window); at--) {
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
