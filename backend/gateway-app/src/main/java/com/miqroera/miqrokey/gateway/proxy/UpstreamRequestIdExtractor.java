package com.miqroera.miqrokey.gateway.proxy;

import java.nio.charset.StandardCharsets;

/**
 * Best-effort upstream request id extraction from the observed response prefix
 * (#623). Providers such as DeepSeek carry the id only in the response body
 * ({@code {"id":"..."}} for OpenAI chat completions — the same key heads the
 * first SSE chunk), never in the {@code x-request-id}/{@code request-id}
 * headers the gateway used to rely on, so usage rows lost their dedup anchor
 * and reconciliation could never match level&nbsp;1.
 *
 * <p>
 * Extraction is read-only, byte-scan based (no JSON parse on the hot path) and
 * returns null on any doubt — a missing id can only degrade reconciliation,
 * never the proxy outcome.
 * </p>
 */
final class UpstreamRequestIdExtractor {

    /** Mirrors the header path's truncation width; longer body ids are skipped. */
    private static final int MAX_ID_LENGTH = 128;

    private static final byte[] NEEDLE = "\"id\"".getBytes(StandardCharsets.US_ASCII);

    private UpstreamRequestIdExtractor() {
    }

    /**
     * Returns the first JSON {@code "id"} string value found in the prefix, or null
     * when absent, empty, unterminated (prefix cut mid-value) or longer than
     * {@value #MAX_ID_LENGTH} characters. Keys merely containing {@code id} (e.g.
     * {@code "request_id"}) never match — the needle is the quoted key.
     */
    static String fromBodyPrefix(byte[] prefix) {
        if (prefix == null || prefix.length == 0) {
            return null;
        }
        int at = indexOf(prefix, NEEDLE, 0);
        while (at >= 0) {
            int i = at + NEEDLE.length;
            while (i < prefix.length && isSpace(prefix[i])) {
                i++;
            }
            if (i < prefix.length && prefix[i] == ':') {
                i++;
                while (i < prefix.length && isSpace(prefix[i])) {
                    i++;
                }
                if (i < prefix.length && prefix[i] == '"') {
                    i++;
                    int start = i;
                    while (i < prefix.length && i - start <= MAX_ID_LENGTH && prefix[i] != '"' && prefix[i] != '\\'
                            && prefix[i] >= 0x20) {
                        i++;
                    }
                    if (i >= prefix.length) {
                        // The prefix was cut mid-value: no id we can trust.
                        return null;
                    }
                    if (prefix[i] == '"' && i > start && i - start <= MAX_ID_LENGTH) {
                        return new String(prefix, start, i - start, StandardCharsets.UTF_8);
                    }
                    // Escaped, empty or overlong value: not a usable id.
                    if (prefix[i] != '"') {
                        at = indexOf(prefix, NEEDLE, at + 1);
                        continue;
                    }
                    return null;
                }
            }
            at = indexOf(prefix, NEEDLE, at + 1);
        }
        return null;
    }

    private static boolean isSpace(byte b) {
        return b == ' ' || b == '\t' || b == '\n' || b == '\r';
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer: for (int i = Math.max(0, from); i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
