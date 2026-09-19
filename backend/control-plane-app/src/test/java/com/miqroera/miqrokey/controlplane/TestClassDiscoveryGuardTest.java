package com.miqroera.miqrokey.controlplane;

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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Surefire auto-discovers only classes whose file name matches {@code Test*},
 * {@code *Test}, {@code *Tests} or {@code *TestCase}. Nothing else in this
 * build widens that set: the {@code -Pintegration} profile only clears
 * {@code <excludedGroups>} (it adds no include), and no JUnit Platform suite
 * re-selects classes. So a {@code @Test} living in a file named any other way
 * is dead code — it is silently skipped by both {@code ./mvnw verify} and
 * {@code ./mvnw verify -Pintegration}, and no report ever mentions it.
 */
class TestClassDiscoveryGuardTest {

    /**
     * Annotation names, anchored so that {@code @Testcontainers} does not count as
     * {@code @Test}.
     */
    private static final Pattern TEST_ANNOTATION = Pattern
            .compile("@(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\n]*");

    /**
     * String literals, so that an annotation <em>named</em> in a string is not read
     * as usage.
     *
     * <p>
     * The quantifiers are possessive on purpose. A source file may contain an odd
     * number of {@code "} characters (a char literal such as {@code '"'} is
     * enough), which leaves a quote that never closes; a backtracking pattern then
     * recurses over the rest of the file and dies with {@link StackOverflowError}
     * instead of reporting a result. Possessive quantifiers make the match linear
     * and let it fail immediately. Newlines terminate the class so that an
     * unterminated quote cannot swallow a whole file either.
     * </p>
     */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\\\n]++|\\\\.)*+\"");

    /**
     * A JUnit Platform suite re-selects classes by annotation. Matched as a real
     * annotation, after stripping comments <em>and</em> string literals — see
     * {@link #isPlatformSuite}.
     */
    private static final Pattern SUITE_ANNOTATION = Pattern.compile("@(Suite|SelectClasses)\\b");

    /**
     * Surefire's default {@code <includes>}; keep in sync with surefire's own
     * defaults.
     */
    private static final List<String> DISCOVERABLE_NAMES = List.of("Test*", "*Test", "*Tests", "*TestCase");

    @Test
    @DisplayName("every test-declaring class is reachable by surefire's default include patterns")
    void testClassesAreDiscoverableBySurefire() {
        Path backend = backendRoot();
        List<Path> testSources = javaSourcesUnder(backend);
        assertTrue(testSources.size() > 100, "expected to scan the whole backend test tree, but found only "
                + testSources.size() + " files under " + backend + " — did the working directory change?");
        List<Path> suiteFiles = testSources.stream().filter(TestClassDiscoveryGuardTest::isPlatformSuite).toList();

        List<String> orphans = new ArrayList<>();
        for (Path source : testSources) {
            String code = withoutComments(read(source));
            if (!TEST_ANNOTATION.matcher(code).find() || isAbstract(code)) {
                continue;
            }
            String name = source.getFileName().toString().replace(".java", "");
            if (DISCOVERABLE_NAMES.stream().anyMatch(pattern -> matches(pattern, name))) {
                continue;
            }
            if (suiteFiles.stream().anyMatch(suite -> selects(suite, name))) {
                continue;
            }
            orphans.add(backend.relativize(source).toString().replace('\\', '/'));
        }

        assertTrue(orphans.isEmpty(),
                "these classes declare tests but their file names match none of " + DISCOVERABLE_NAMES
                        + ", so surefire never runs them (rename the file, or add it to a JUnit Platform suite): "
                        + orphans);
    }

    private static boolean matches(String pattern, String name) {
        return name.matches(Pattern.quote(pattern).replace("*", "\\E.*\\Q"));
    }

    /**
     * Testcontainers slices and other helpers are abstract on purpose; surefire
     * cannot run them.
     */
    private static boolean isAbstract(String code) {
        return Pattern.compile("\\babstract\\s+class\\b").matcher(code).find();
    }

    /**
     * Note the double stripping. This guard's own source contains the literal
     * {@code "@Suite"} in {@link #SUITE_ANNOTATION}, so a naive
     * {@code contains("@Suite")} over raw text makes the guard count
     * <em>itself</em> as a suite — and {@link #selects} then matches any orphan
     * whose name is a substring of this file ({@code Test}, {@code List},
     * {@code Path}, {@code Files}, {@code Stream}, {@code Pattern}…), silently
     * excusing exactly the dead tests this class exists to catch.
     */
    private static boolean isPlatformSuite(Path source) {
        return SUITE_ANNOTATION.matcher(withoutStrings(withoutComments(read(source)))).find();
    }

    /**
     * True when the suite names this class as a whole identifier, not as a
     * substring.
     */
    private static boolean selects(Path suite, String name) {
        String code = withoutStrings(withoutComments(read(suite)));
        return Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(code).find();
    }

    private static String withoutComments(String code) {
        return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(code).replaceAll("")).replaceAll("");
    }

    /**
     * Replaces each string literal with an empty one, keeping indices/lengths
     * roughly aligned.
     */
    private static String withoutStrings(String code) {
        return STRING_LITERAL.matcher(code).replaceAll("\"\"");
    }

    private static Path backendRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isDirectory(candidate.resolve("backend").resolve("domain"))) {
            candidate = candidate.getParent();
        }
        assertTrue(candidate != null,
                "could not locate the repository root (no backend/domain above " + Path.of("").toAbsolutePath() + ")");
        return candidate.resolve("backend");
    }

    private static List<Path> javaSourcesUnder(Path backend) {
        try (Stream<Path> modules = Files.list(backend)) {
            return modules.map(module -> module.resolve("src").resolve("test").resolve("java"))
                    .filter(Files::isDirectory).flatMap(root -> {
                        try (Stream<Path> files = Files.walk(root)) {
                            return files.filter(path -> path.toString().endsWith(".java")).toList().stream();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }).filter(path -> !path.getFileName().toString().equals("package-info.java")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path source) {
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
