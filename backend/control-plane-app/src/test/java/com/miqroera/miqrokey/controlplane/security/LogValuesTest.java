package com.miqroera.miqrokey.controlplane.security;

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
        assertThat(LogValues.forLog("newbie\nFORGED")).isEqualTo("newbie?FORGED");
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
    @DisplayName("the delimiters of a structured log line are neutralized")
    void delimitersAreNeutralized() {
        assertThat(LogValues.forLog("x[]=user")).isEqualTo("x???user");
        assertThat(LogValues.forLog("user,requestId=other")).isEqualTo("user?requestId?other");
    }

    @Test
    @DisplayName("the default bound fits a username and still bounds an oversized one")
    void defaultBoundMatchesTheUsernameColumn() {
        String longestUsername = "u".repeat(LogValues.DEFAULT_MAX_LENGTH);
        assertThat(LogValues.forLog(longestUsername)).isEqualTo(longestUsername);

        String bounded = LogValues.forLog("x".repeat(LogValues.DEFAULT_MAX_LENGTH + 50));
        assertThat(bounded).hasSize(LogValues.DEFAULT_MAX_LENGTH + 1);
        assertThat(bounded).startsWith("x".repeat(LogValues.DEFAULT_MAX_LENGTH));
        assertThat(bounded).doesNotEndWith("x");
    }

    @Test
    @DisplayName("an explicit bound is honoured so a channel can stay tighter")
    void explicitBoundIsHonoured() {
        String bounded = LogValues.forLog("y".repeat(100), 64);

        assertThat(bounded).hasSize(65);
        assertThat(bounded).startsWith("y".repeat(64));
        assertThat(LogValues.forLog("short", 64)).isEqualTo("short");
    }

    @Test
    @DisplayName("null stays null so callers keep their own placeholder")
    void nullPassesThrough() {
        assertThat(LogValues.forLog(null)).isNull();
        assertThat(LogValues.forLog(null, 64)).isNull();
    }

    @Test
    @DisplayName("an ordinary username is left alone")
    void ordinaryValuesAreUnchanged() {
        assertThat(LogValues.forLog("newbie")).isEqualTo("newbie");
        assertThat(LogValues.forLog("li.wei@example.com")).isEqualTo("li.wei@example.com");
    }
}
