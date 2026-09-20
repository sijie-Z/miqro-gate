package com.miqroera.miqrokey.controlplane.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code application/problem+json} envelope written outside MVC has one
 * writer (#1011).
 *
 * <p>
 * Every isolation defect this package has had in this area came from the same
 * shape: the envelope spliced by hand, with a private copy of the escape rule.
 * #447 found a copy whose control-character branch was missing; #949/#951 fixed
 * two filters and left six writers behind, one of which had reduced the rule to
 * two {@code replace} calls and so stopped escaping the C0 range entirely
 * (#1011). Fixing instances one at a time did not converge, because the next
 * writer was always free to re-derive the rule — incorrectly.
 * </p>
 *
 * <p>
 * So this guards the <em>structure</em> rather than another envelope: a scan of
 * this package's sources. It reads files, so it needs no Spring context and no
 * database, and it fails naming the file that broke the rule.
 * </p>
 */
@DisplayName("the problem+json envelope has exactly one writer (#1011)")
class ProblemEnvelopeSingleWriterGuardTest {

    /** The package that owns the envelope. */
    private static final String PACKAGE_PATH = "control-plane-app/src/main/java/com/miqroera/miqrokey/controlplane/security";

    /** The one file allowed to name the envelope's fixed {@code type}. */
    private static final String WRITER = "ProblemJson.java";

    private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\n]*");

    /**
     * A hand-rolled copy of the escape rule, i.e. the thing that keeps going wrong.
     */
    private static final Pattern OWN_ESCAPE = Pattern.compile("\\bstatic\\s+String\\s+escapeJson\\s*\\(");

    @Test
    @DisplayName("only ProblemJson names the envelope type")
    void onlyTheSharedWriterBuildsTheEnvelope() {
        List<String> offenders = new ArrayList<>();
        for (Path source : packageSources()) {
            // Substring, not "a string literal equal to about:blank": the spliced
            // writers embedded the type inside a longer template
            // (`String.format("{\"type\":\"about:blank\",…")`), so an equality match
            // on the literal sailed straight past every one of them. Match what the
            // defect actually looks like.
            if (withoutComments(read(source)).contains("about:blank")
                    && !source.getFileName().toString().equals(WRITER)) {
                offenders.add(source.getFileName().toString());
            }
        }
        assertThat(offenders)
                .as("these files splice the problem+json envelope themselves; build it through %s.of(...) instead",
                        WRITER)
                .isEmpty();
    }

    @Test
    @DisplayName("no file in the package carries its own escapeJson copy")
    void noLocalEscapeCopies() {
        List<String> offenders = new ArrayList<>();
        for (Path source : packageSources()) {
            if (OWN_ESCAPE.matcher(withoutComments(read(source))).find()) {
                offenders.add(source.getFileName().toString());
            }
        }
        assertThat(offenders).as("a private escape rule is how the half-escaped copy survived (#447, #1011)").isEmpty();
    }

    private static String withoutComments(String code) {
        return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(code).replaceAll("")).replaceAll("");
    }

    private static List<Path> packageSources() {
        Path dir = repositoryRoot().resolve("backend").resolve(PACKAGE_PATH);
        assertThat(dir).as("the security package should exist at %s", dir).isDirectory();
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isDirectory(candidate.resolve("backend").resolve("domain"))) {
            candidate = candidate.getParent();
        }
        assertThat(candidate).as("could not locate the repository root above %s", Path.of("").toAbsolutePath())
                .isNotNull();
        return candidate;
    }

    private static String read(Path source) {
        try {
            return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
