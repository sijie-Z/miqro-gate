package com.miqroera.miqrokey.domain.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Strict, dependency-free JSON object reader for the JWT header and claims
 * documents. The domain module must not depend on serialization libraries
 * (enforced by {@code ModuleDependencyTest.domainMustNotDependOnJackson}), so
 * this scanner validates a whole document in one pass and exposes its top-level
 * members without building a tree.
 *
 * <p>
 * Strictness is deliberate and fail-closed: UTF-8 must decode cleanly, the
 * document must be exactly one JSON object with no trailing bytes, numbers must
 * follow the JSON grammar, nesting is capped and nothing is ever coerced — a
 * member of an unexpected JSON type is reported as absent, never converted to
 * text or truncated to a number.
 * </p>
 */
final class MinimalJson {

    /** Nesting cap: JWT header and claims are flat; anything deeper is hostile. */
    private static final int MAX_DEPTH = 32;

    /** Present member whose value is neither a JSON string nor a JSON integer. */
    private static final Object OTHER = new Object();

    private final Map<String, Object> members;

    private MinimalJson(Map<String, Object> members) {
        this.members = members;
    }

    /**
     * Parses {@code document} into a top-level object view; null when it is not
     * one.
     */
    static MinimalJson parse(byte[] document) {
        try {
            String text = decodeUtf8(document);
            if (text == null) {
                return null;
            }
            Cursor cursor = new Cursor(text);
            Map<String, Object> members = new HashMap<>();
            cursor.skipWhitespace();
            cursor.parseObject(members, 1);
            cursor.skipWhitespace();
            return cursor.atEnd() ? new MinimalJson(members) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** True when the member exists with a non-null value. */
    boolean has(String key) {
        return members.containsKey(key) && members.get(key) != null;
    }

    /**
     * Top-level string member; null when absent, JSON null, or not a JSON string.
     */
    String string(String key) {
        return members.get(key) instanceof String value ? value : null;
    }

    /**
     * Top-level integer member; null when absent, JSON null, or not a JSON integer.
     */
    Long integer(String key) {
        return members.get(key) instanceof Long value ? value : null;
    }

    private static String decodeUtf8(byte[] document) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(document)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /**
     * Thrown on any syntax violation; no message and no stack trace (hostile
     * input).
     */
    private static final class JsonSyntaxException extends RuntimeException {
        JsonSyntaxException() {
            super(null, null, false, false);
        }
    }

    private static final class Cursor {

        private final String text;
        private int pos;

        Cursor(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos == text.length();
        }

        void skipWhitespace() {
            while (pos < text.length() && isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        /**
         * Parses one object; its members land in {@code sink}, or are validated and
         * discarded when {@code sink} is null (nested objects).
         */
        void parseObject(Map<String, Object> sink, int depth) {
            if (depth > MAX_DEPTH) {
                throw new JsonSyntaxException();
            }
            expect('{');
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                Object value = parseValue(depth);
                if (sink != null) {
                    sink.put(key, value);
                }
                skipWhitespace();
                char separator = peek();
                if (separator == ',') {
                    pos++;
                } else if (separator == '}') {
                    pos++;
                    return;
                } else {
                    throw new JsonSyntaxException();
                }
            }
        }

        /** Validates and consumes one value; only top-level scalars are exposed. */
        private Object parseValue(int depth) {
            return switch (peek()) {
                case '{' -> {
                    parseObject(null, depth + 1);
                    yield OTHER;
                }
                case '[' -> {
                    parseArray(depth + 1);
                    yield OTHER;
                }
                case '"' -> parseString();
                case 't' -> {
                    expectLiteral("true");
                    yield OTHER;
                }
                case 'f' -> {
                    expectLiteral("false");
                    yield OTHER;
                }
                case 'n' -> {
                    expectLiteral("null");
                    yield null;
                }
                default -> parseNumber();
            };
        }

        private void parseArray(int depth) {
            if (depth > MAX_DEPTH) {
                throw new JsonSyntaxException();
            }
            expect('[');
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return;
            }
            while (true) {
                skipWhitespace();
                parseValue(depth);
                skipWhitespace();
                char separator = peek();
                if (separator == ',') {
                    pos++;
                } else if (separator == ']') {
                    pos++;
                    return;
                } else {
                    throw new JsonSyntaxException();
                }
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                char ch = next();
                if (ch == '"') {
                    return out.toString();
                }
                if (ch == '\\') {
                    out.append(parseEscape());
                } else if (ch < 0x20) {
                    throw new JsonSyntaxException();
                } else {
                    out.append(ch);
                }
            }
        }

        private char parseEscape() {
            return switch (next()) {
                case '"' -> '"';
                case '\\' -> '\\';
                case '/' -> '/';
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'u' -> parseUnicodeEscape();
                default -> throw new JsonSyntaxException();
            };
        }

        private char parseUnicodeEscape() {
            int code = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(next(), 16);
                if (digit < 0) {
                    throw new JsonSyntaxException();
                }
                code = (code << 4) | digit;
            }
            return (char) code;
        }

        /** JSON number grammar; non-integral or out-of-range numbers become OTHER. */
        private Object parseNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            char first = peek();
            if (first == '0') {
                pos++;
            } else if (first >= '1' && first <= '9') {
                skipDigits();
            } else {
                throw new JsonSyntaxException();
            }
            boolean integral = true;
            if (pos < text.length() && text.charAt(pos) == '.') {
                integral = false;
                pos++;
                requireDigit();
                skipDigits();
            }
            if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                integral = false;
                pos++;
                if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                    pos++;
                }
                requireDigit();
                skipDigits();
            }
            if (!integral) {
                return OTHER;
            }
            try {
                return Long.parseLong(text.substring(start, pos));
            } catch (NumberFormatException e) {
                return OTHER;
            }
        }

        private void skipDigits() {
            while (pos < text.length() && isDigit(text.charAt(pos))) {
                pos++;
            }
        }

        private void requireDigit() {
            if (pos >= text.length() || !isDigit(text.charAt(pos))) {
                throw new JsonSyntaxException();
            }
        }

        private void expectLiteral(String literal) {
            if (!text.startsWith(literal, pos)) {
                throw new JsonSyntaxException();
            }
            pos += literal.length();
        }

        private void expect(char expected) {
            if (next() != expected) {
                throw new JsonSyntaxException();
            }
        }

        private char next() {
            if (pos >= text.length()) {
                throw new JsonSyntaxException();
            }
            return text.charAt(pos++);
        }

        private char peek() {
            if (pos >= text.length()) {
                throw new JsonSyntaxException();
            }
            return text.charAt(pos);
        }

        private static boolean isWhitespace(char ch) {
            return ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r';
        }

        private static boolean isDigit(char ch) {
            return ch >= '0' && ch <= '9';
        }
    }
}
