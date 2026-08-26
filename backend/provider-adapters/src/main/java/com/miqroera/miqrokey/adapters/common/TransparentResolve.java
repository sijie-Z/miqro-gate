package com.miqroera.miqrokey.adapters.common;

import com.miqroera.miqrokey.spi.InboundRequest;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared transparent-proxy request preparation (G3.2): header sanitization and
 * query re-encoding. Both operations are identical across the OpenAI/Anthropic
 * compatible adapters; only the per-product path normalization stays with the
 * adapter.
 */
public final class TransparentResolve {

    private TransparentResolve() {
    }

    /**
     * Builds the forwarded header map (lowercase names) from an inbound request,
     * dropping every credential header the product's injection strips. The gateway
     * injects the real credential later; client keys never reach the upstream.
     */
    public static Map<String, String> headers(InboundRequest request, Set<String> stripInboundHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : request.headers().entrySet()) {
            if (stripInboundHeaders.contains(entry.getKey().toLowerCase())) {
                continue;
            }
            List<String> values = entry.getValue();
            if (!values.isEmpty()) {
                // TargetRequest contract: header names are lowercase.
                headers.put(entry.getKey().toLowerCase(), values.get(0));
            }
        }
        return headers;
    }

    /**
     * Re-encodes the inbound decoded query parameters into a raw query string
     * (TargetRequest contract: raw, without leading {@code ?}). Keys and values
     * were decoded with UTF-8 percent-decoding; the round-trip uses UTF-8 form
     * encoding with spaces as {@code %20}.
     */
    public static String queryString(Map<String, List<String>> query) {
        if (query.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : query.entrySet()) {
            for (String value : entry.getValue()) {
                if (sb.length() > 0) {
                    sb.append('&');
                }
                sb.append(encode(entry.getKey()));
                if (value != null) {
                    sb.append('=').append(encode(value));
                }
            }
        }
        return sb.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
