// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine.text;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Unit tests for {@link TextLayout} (step8k-prompt.md &sect;C): widths, spacing, newline, and the two caps. */
class TextLayoutTest {

    @Test
    void singleCharWidthMatchesGlyphWidth() {
        TextLayout.Layout layout = TextLayout.layout("I", 1);
        assertEquals(5, layout.width());
        assertEquals(7, layout.height());
        assertEquals(1, layout.glyphs().size());
        assertEquals(0, layout.glyphs().get(0).col());
    }

    @Test
    void twoCharsAreSeparatedBySpacing() {
        TextLayout.Layout layout = TextLayout.layout("II", 1);
        // I is 5 wide; second glyph starts after 5 + spacing(1) = 6
        assertEquals(2, layout.glyphs().size());
        assertEquals(0, layout.glyphs().get(0).col());
        assertEquals(6, layout.glyphs().get(1).col());
        assertEquals(11, layout.width());
    }

    @Test
    void widerSpacingPushesGlyphsFurtherApart() {
        TextLayout.Layout layout = TextLayout.layout("II", 3);
        assertEquals(0, layout.glyphs().get(0).col());
        assertEquals(8, layout.glyphs().get(1).col()); // 5 + spacing(3)
        assertEquals(13, layout.width());
    }

    @Test
    void spaceCharacterIsThreeColumnsWideAndEmitsNoGlyph() {
        TextLayout.Layout layout = TextLayout.layout("I I", 1);
        // I(5) + spacing(1) + space(3) + spacing(1) + I(5) = 15
        assertEquals(2, layout.glyphs().size());
        assertEquals(0, layout.glyphs().get(0).col());
        assertEquals(10, layout.glyphs().get(1).col()); // 5 + 1(spacing) + 3(space) + 1(spacing)
        assertEquals(15, layout.width());
    }

    @Test
    void newlineStartsANewLineOneRowBelow() {
        TextLayout.Layout layout = TextLayout.layout("I\nI", 1);
        assertEquals(2, layout.lineCount());
        // 7 + lineSpacing(1) + 7 = 15
        assertEquals(15, layout.height());
        assertEquals(0, layout.glyphs().get(0).line());
        assertEquals(1, layout.glyphs().get(1).line());
        assertEquals(0, layout.glyphs().get(1).col());
    }

    @Test
    void fourLinesIsAllowed() {
        TextLayout.Layout layout = TextLayout.layout("I\nI\nI\nI", 1);
        assertEquals(4, layout.lineCount());
        assertEquals(4 * 7 + 3, layout.height());
    }

    @Test
    void fiveLinesThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TextLayout.layout("I\nI\nI\nI\nI", 1));
        assertEquals("text must have at most 4 lines, got 5", e.getMessage());
    }

    @Test
    void sixtyFourCharsIsAllowed() {
        String text = "A".repeat(64);
        TextLayout.Layout layout = TextLayout.layout(text, 0);
        assertEquals(64, layout.glyphs().size());
    }

    @Test
    void sixtyFiveCharsThrows() {
        String text = "A".repeat(65);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> TextLayout.layout(text, 1));
        assertEquals("text must be 1-64 characters after trimming, got 65", e.getMessage());
    }

    @Test
    void emptyAfterTrimThrows() {
        assertThrows(IllegalArgumentException.class, () -> TextLayout.layout("   ", 1));
    }

    @Test
    void leadingAndTrailingWhitespaceIsTrimmedBeforeLengthCheck() {
        String text = " " + "A".repeat(64) + " ";
        TextLayout.Layout layout = TextLayout.layout(text, 0);
        assertEquals(64, layout.glyphs().size());
    }

    @Test
    void placedGlyphsPreserveCodePoints() {
        TextLayout.Layout layout = TextLayout.layout("AB", 1);
        List<TextLayout.PlacedGlyph> glyphs = layout.glyphs();
        assertEquals('A', glyphs.get(0).codePoint());
        assertEquals('B', glyphs.get(1).codePoint());
    }

    @Test
    void mixedHeightLineIsAsTallAsItsTallestGlyphAndBottomAligns() {
        // "A" (7 rows) next to a CJK character (12 rows via AWT): the line is 12 rows, A sits on the baseline.
        TestFonts.assumeSystemFontCanDisplay(0x6b22);
        TextLayout.Layout layout = TextLayout.layout("A\u6b22", 1);
        assertEquals(12, layout.height());
        assertEquals(12, layout.firstLineHeight());
        TextLayout.PlacedGlyph a = layout.glyphs().get(0);
        TextLayout.PlacedGlyph cjk = layout.glyphs().get(1);
        assertEquals(5, a.row());
        assertEquals(0, cjk.row());
        assertEquals(12, cjk.height());
    }

    @Test
    void secondLineStartsBelowTheFirstLinesRows() {
        TextLayout.Layout layout = TextLayout.layout("A\nB", 1);
        assertEquals(7, layout.firstLineHeight());
        assertEquals(0, layout.glyphs().get(0).row());
        assertEquals(8, layout.glyphs().get(1).row());
    }
}
