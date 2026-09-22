// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine.text;

import java.awt.Font;
import java.util.Locale;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Test-only helper: skips the calling test (rather than failing it) when this JVM's logical
 * SANS_SERIF font - the fallback {@link AwtGlyphs} uses whenever no {@code
 * engine.text-font-file} is configured, which is how every unit test in this package runs,
 * {@link FontSourceTest} aside - cannot display a given non-ASCII code point. Meaningful on a
 * developer machine with real fonts installed (verified on this Mac); skips rather than
 * false-failing on a bare CI image with no CJK font (the GitHub Actions Ubuntu runner, before
 * {@code fonts-noto-cjk} is installed there).
 */
final class TestFonts {

    private TestFonts() {
    }

    static void assumeSystemFontCanDisplay(int codePoint) {
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        assumeTrue(font.canDisplay(codePoint), () -> "this JVM's system font has no glyph for U+"
                + Integer.toHexString(codePoint).toUpperCase(Locale.ROOT)
                + " (no CJK font installed on this machine/CI runner)");
    }
}
