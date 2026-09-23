package com.miqroera.miqrokey.gateway.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LogValues.forLog flattens caller-supplied values for the log line")
class LogValuesTest {

    /** Control characters beyond the usual \n / \r / \t, built without literals. */
    private static String charOf(int codePoint) {
        return String.valueOf((char) codePoint);
    }

    @Test
    @DisplayName("line breaks, tabs and other control characters cannot survive")
    void controlCharactersAreFlattened() {
        assertThat(LogValues.forLog("tools/list\nFORGED")).isEqualTo("tools/list?FORGED");
        assertThat(LogValues.forLog("a\r\nb")).isEqualTo("a??b");
        assertThat(LogValues.forLog("a\tb")).isEqualTo("a?b");
        assertThat(LogValues.forLog("a" + charOf(0x0B) + "b")).isEqualTo("a?b"); // vertical tab
        assertThat(LogValues.forLog("a" + charOf(0x1B) + "b")).isEqualTo("a?b"); // escape
        assertThat(LogValues.forLog("a" + charOf(0x07) + "b")).isEqualTo("a?b"); // bell
        // U+2028 / U+2029 are line separators for some viewers but are not \p{Cc}.
        assertThat(LogValues.forLog("a" + charOf(0x2028) + "b" + charOf(0x2029) + "c")).isEqualTo("a?b?c");
        // A plain space is not a control character and must survive.
        assertThat(LogValues.forLog("a b")).isEqualTo("a b");
    }

    @Test
    @DisplayName("the key=value delimiters of a log line are neutralized")
    void delimitersAreNeutralized() {
        assertThat(LogValues.forLog("x[]=service")).isEqualTo("x???service");
        assertThat(LogValues.forLog("tools/call,consumer=other")).isEqualTo("tools/call?consumer?other");
    }

    @Test
    @DisplayName("an oversized value is bounded so it cannot amplify the log")
    void oversizedValuesAreBounded() {
        String bounded = LogValues.forLog("x".repeat(LogValues.MAX_LENGTH + 50));

        assertThat(bounded).hasSize(LogValues.MAX_LENGTH + 1);
        assertThat(bounded).startsWith("x".repeat(LogValues.MAX_LENGTH));
        assertThat(bounded).doesNotEndWith("x");
    }

    @Test
    @DisplayName("null stays null so callers keep their own placeholder")
    void nullPassesThrough() {
        assertThat(LogValues.forLog(null)).isNull();
    }

    @Test
    @DisplayName("an ordinary method or tool name is left alone")
    void ordinaryValuesAreUnchanged() {
        assertThat(LogValues.forLog("tools/call")).isEqualTo("tools/call");
        assertThat(LogValues.forLog("mcp__demo__read_file")).isEqualTo("mcp__demo__read_file");
    }
}
