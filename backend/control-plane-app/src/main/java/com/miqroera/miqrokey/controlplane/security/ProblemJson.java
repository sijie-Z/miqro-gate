package com.miqroera.miqrokey.controlplane.security;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the {@code application/problem+json} envelopes written by the filters
 * in this package (issue #445, extended by PH16, single writer since #1011).
 *
 * <p>
 * These writers live in servlet filters, i.e. outside MVC, so they cannot
 * return a {@code ProblemDetail} and let Spring serialize it — they have to
 * write the response themselves. They build the body through Jackson rather
 * than splicing it with {@link String#format}: every envelope here carries
 * client-controlled text (the echoed {@code X-Request-Id} header), and "escape
 * each value by hand" is a rule that has to be re-applied correctly every time
 * a field is added.
 * </p>
 *
 * <p>
 * The hand-escaped form that preceded this quoted only {@code "} and {@code \}.
 * That is not enough: a literal HTAB (0x09) survives Tomcat's header parser and
 * reaches {@code getHeader} verbatim, so it was reflected raw and the whole
 * envelope stopped being parseable — Jackson rejects it with
 * {@code Illegal unquoted character
 * ((CTRL-CHAR, code 9))}. Serializing removes the class of defect rather than
 * the instance that happened to be noticed.
 * </p>
 *
 * <p>
 * #1011 converged the six writers that were still splicing their own copy of
 * this body, each with its own private {@code escapeJson} (or, in
 * {@code AdminIpAllowlistFilter}, an inline two-{@code replace} chain that had
 * lost the control-character branch). They keep their historical field set:
 * {@code detail} stays <em>absent</em> rather than becoming {@code null}, which
 * is why {@link #of} omits it. Six copies of one escape rule is how the
 * half-escaped one survived; there is now one place to get it wrong.
 * </p>
 */
final class ProblemJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProblemJson() {
    }

    /**
     * The RFC 9457 body for these fields, with the fixed {@code type}. Field order
     * is the order below, so the wire form stays byte-stable for callers that read
     * it as text rather than parsing it.
     *
     * <p>
     * {@code detail} is optional in RFC 9457 and omitted when {@code null} — not
     * written as JSON {@code null}. The filters that predate this class never
     * carried a {@code detail}, and dropping in a serializer must not quietly add a
     * member to their responses.
     * </p>
     */
    static String of(int status, String title, String code, String detail, String requestId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", title);
        body.put("status", status);
        body.put("code", code);
        if (detail != null) {
            body.put("detail", detail);
        }
        body.put("requestId", requestId);
        try {
            return MAPPER.writeValueAsString(body);
        } catch (JacksonException e) {
            // Every value is a String or an int; this cannot happen. Surfacing it beats
            // writing a half-built envelope the caller would then try to parse.
            throw new IllegalStateException("problem+json envelope is not serializable", e);
        }
    }
}
