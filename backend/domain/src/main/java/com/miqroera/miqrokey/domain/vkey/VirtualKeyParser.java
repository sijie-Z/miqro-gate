package com.miqroera.miqrokey.domain.vkey;

import com.miqroera.miqrokey.domain.crypto.impl.SecretWiping;

import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Parses presented Virtual Keys of the label-routing format:
 *
 * <pre>
 * mqk_live_&lt;publicKeyId&gt;_&lt;secret&gt;.&lt;projectTag&gt;
 * </pre>
 *
 * <h2>Fixed-length parsing</h2> Both {@code publicKeyId} (base64url of 16 bytes
 * → 22 chars) and {@code secret} (base64url of 32 bytes → 43 chars) use the
 * URL-safe alphabet, which includes {@code _}. Positional parsing is therefore
 * required: after the {@code mqk_live_} prefix, the public key ID is exactly 22
 * characters, the separator is exactly one {@code _}, the secret is exactly 43
 * characters, and the label is everything after the first {@code .} following
 * the secret. A tag may itself contain {@code _} (which is why the core is
 * parsed positionally), but never {@code .}: a dot inside the tag inflates the
 * core past its fixed length and the whole key is rejected as malformed.
 *
 * <h2>Failure behavior</h2> Any malformed input yields
 * {@link VirtualKeyParseResult#invalid()} — never an exception — so callers can
 * uniformly reject with {@code VIRTUAL_KEY_INVALID} without leaking which part
 * failed. The label is intentionally NOT included in the HMAC digest: the label
 * only routes, and {@code key_project_binding} is the authorization authority.
 */
public final class VirtualKeyParser {

    public static final String PREFIX = "mqk_live_";
    public static final int PUBLIC_KEY_ID_CHARS = 22; // base64url(16 bytes)
    public static final int SECRET_CHARS = 43; // base64url(32 bytes)

    private static final Pattern TAG_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern BASE64URL_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private VirtualKeyParser() {
    }

    /**
     * Parses the presented key string. Never throws; malformed input returns an
     * invalid result.
     *
     * @param presented
     *            the raw Authorization bearer value (or x-api-key value)
     * @return parse result (raw secret is caller-owned; clear after use)
     */
    public static VirtualKeyParseResult parse(String presented) {
        if (presented == null || presented.length() < PREFIX.length() + PUBLIC_KEY_ID_CHARS + 1 + SECRET_CHARS + 2) {
            return VirtualKeyParseResult.invalid();
        }
        if (!presented.startsWith(PREFIX)) {
            return VirtualKeyParseResult.invalid();
        }

        String body = presented.substring(PREFIX.length());
        if (body.length() < PUBLIC_KEY_ID_CHARS + 1 + SECRET_CHARS + 2) {
            return VirtualKeyParseResult.invalid();
        }
        // Split label off from the LAST '.' (label may contain '_').
        int lastDot = body.lastIndexOf('.');
        if (lastDot < PUBLIC_KEY_ID_CHARS + 1 + SECRET_CHARS) {
            return VirtualKeyParseResult.invalid();
        }
        String projectTag = body.substring(lastDot + 1);
        if (!TAG_PATTERN.matcher(projectTag).matches()) {
            return VirtualKeyParseResult.invalid();
        }

        String core = body.substring(0, lastDot);
        if (core.length() != PUBLIC_KEY_ID_CHARS + 1 + SECRET_CHARS) {
            return VirtualKeyParseResult.invalid();
        }
        if (core.charAt(PUBLIC_KEY_ID_CHARS) != '_') {
            return VirtualKeyParseResult.invalid();
        }

        String publicKeyId = core.substring(0, PUBLIC_KEY_ID_CHARS);
        String secretPart = core.substring(PUBLIC_KEY_ID_CHARS + 1);
        if (!BASE64URL_PATTERN.matcher(publicKeyId).matches() || !BASE64URL_PATTERN.matcher(secretPart).matches()) {
            return VirtualKeyParseResult.invalid();
        }

        byte[] rawSecret = decodeStrict(secretPart, 32);
        if (rawSecret == null) {
            return VirtualKeyParseResult.invalid();
        }
        // publicKeyId must decode to 16 bytes; reject non-canonical encodings.
        byte[] pkBytes = decodeStrict(publicKeyId, 16);
        if (pkBytes == null) {
            SecretWiping.clearArray(rawSecret);
            return VirtualKeyParseResult.invalid();
        }
        SecretWiping.clearArray(pkBytes);

        return new VirtualKeyParseResult(publicKeyId, rawSecret, projectTag, true);
    }

    /**
     * Strict base64url decode: re-encodes and compares so non-canonical (e.g.
     * padded) encodings are rejected. Returns null on any failure.
     */
    private static byte[] decodeStrict(String text, int expectedBytes) {
        try {
            byte[] decoded = URL_DECODER.decode(text);
            if (decoded.length != expectedBytes) {
                return null;
            }
            String canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(decoded);
            if (!canonical.equals(text)) {
                return null;
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
