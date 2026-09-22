// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine.text;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Font;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Unit tests for {@link FontSource} (step8n-prompt.md &sect;C): empty path keeps the system-font
 * fallback, a real font file loads, and neither a missing path nor a non-font file ever throws -
 * every failure path is a logged warning and a fallback to {@code null} ({@link AwtGlyphs} then
 * uses the JVM's logical SANS_SERIF), never an exception that could take the server down.
 *
 * <p>There is no JDK-bundled font to point at (the JDK ships none), so {@link #findRealFont()}
 * scans a handful of well-known OS font directories for a real {@code .ttf}/{@code .otf}/{@code
 * .ttc} on the machine running the test, and every test that needs one calls {@link
 * org.junit.jupiter.api.Assumptions#assumeTrue} to skip itself (rather than fail) when none is
 * found - meaningful on a developer machine (verified on this Mac, against
 * {@code /System/Library/Fonts}), skipped rather than false-failing on a bare CI image.
 */
class FontSourceTest {

    private static final Logger LOGGER = Logger.getLogger("FontSourceTest");

    private static final List<String> CANDIDATE_DIRS = List.of(
            "/System/Library/Fonts",  // macOS
            "/Library/Fonts",         // macOS (user/admin installed)
            "/usr/share/fonts",       // Linux
            "/usr/local/share/fonts", // Linux
            "C:\\Windows\\Fonts");    // Windows

    private static Path findRealFont() {
        for (String dir : CANDIDATE_DIRS) {
            Path root = Path.of(dir);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root, 3)) {
                Optional<Path> found = walk
                        .filter(Files::isRegularFile)
                        .filter(FontSourceTest::looksLikeFont)
                        .findFirst();
                if (found.isPresent()) {
                    return found.get();
                }
            } catch (IOException e) {
                // A permission-denied subtree etc.: keep looking under the other candidate roots.
            }
        }
        return null;
    }

    private static boolean looksLikeFont(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".ttf") || name.endsWith(".otf") || name.endsWith(".ttc");
    }

    /** Forces {@link FontSource#configure} to actually (re)load on the next call, regardless of what ran before this test. */
    private static void resetToEmpty() {
        FontSource.configure("__FontSourceTest_reset_marker__", null, LOGGER);
        FontSource.configure("", null, LOGGER);
    }

    /**
     * {@link FontSource} is a process-wide static holder (see its class javadoc), so a test class
     * that configures a real font must leave it back at the default empty state afterwards -
     * otherwise a later test class in the same JVM (Gradle normally runs all unit tests in one
     * process) would see {@link AwtGlyphs} silently rendering through whatever font this class last
     * configured instead of the system SANS_SERIF fallback every other text test assumes.
     */
    @AfterAll
    static void resetGlobalStateForOtherTestClasses() {
        resetToEmpty();
    }

    @Test
    void emptyPathKeepsTheSystemFontFallbackWithNoError() {
        resetToEmpty();
        assertNull(FontSource.font());
    }

    @Test
    void aRealFontFileLoads() {
        Path font = findRealFont();
        assumeTrue(font != null, "no .ttf/.otf/.ttc found under any of " + CANDIDATE_DIRS + " on this machine");

        resetToEmpty();
        FontSource.configure(font.toString(), null, LOGGER);
        assertNotNull(FontSource.font(), "a real font file must load into a non-null Font");
    }

    @Test
    void aMissingPathFallsBackWithNoException() {
        resetToEmpty();
        FontSource.configure("/no/such/file/definitely-missing.ttf", null, LOGGER);
        assertNull(FontSource.font());
    }

    @Test
    void aNonFontFileFallsBackWithNoException(@TempDir Path tempDir) throws IOException {
        Path notAFont = tempDir.resolve("not-a-font.ttf");
        Files.writeString(notAFont, "this is definitely not a TrueType/OpenType font file");

        resetToEmpty();
        FontSource.configure(notAFont.toString(), null, LOGGER);
        assertNull(FontSource.font());
    }

    @Test
    void configureIgnoresAnUnchangedPathString() {
        Path font = findRealFont();
        assumeTrue(font != null, "no .ttf/.otf/.ttc found under any of " + CANDIDATE_DIRS + " on this machine");

        resetToEmpty();
        FontSource.configure(font.toString(), null, LOGGER);
        Font first = FontSource.font();
        assertNotNull(first);

        // Same path string again must not re-derive a new Font instance - FontSource caches by
        // path string only (see the class javadoc): a file changed in place at the same path is
        // not picked up by this call alone.
        FontSource.configure(font.toString(), null, LOGGER);
        assertSame(first, FontSource.font());
    }

    @Test
    void relativePathResolvesAgainstTheGivenDataFolder(@TempDir Path dataFolder) throws IOException {
        Path font = findRealFont();
        assumeTrue(font != null, "no .ttf/.otf/.ttc found under any of " + CANDIDATE_DIRS + " on this machine");

        Path copy = dataFolder.resolve("relative.ttf");
        Files.copy(font, copy);

        resetToEmpty();
        FontSource.configure("relative.ttf", dataFolder, LOGGER);
        assertNotNull(FontSource.font(), "a relative path must resolve against dataFolder");
    }
}
