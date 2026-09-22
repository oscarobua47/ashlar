// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AwtGlyphs} (step8k-prompt.md &sect;A): a non-ASCII code point renders to a
 * non-blank bitmap, capped at {@link AwtGlyphs#MAX_WIDTH} columns and exactly {@link
 * AwtGlyphs#HEIGHT} rows tall. Whether this passes at all is itself the answer to "does this
 * machine's Java have fonts for non-ASCII text" - see the step8k report.
 */
class AwtGlyphsTest {

    @Test
    void chineseCharacterRendersToANonBlankBitmapCappedAtNineColumns() {
        // U+6B22 (CJK "huan" as in "welcome" - the first character of the test-server check below).
        Glyph g = Glyphs.glyphFor(0x6B22);
        assertEquals(AwtGlyphs.HEIGHT, g.height());
        assertTrue(g.width() >= 1 && g.width() <= AwtGlyphs.MAX_WIDTH, "width was " + g.width());
        boolean anyInk = false;
        for (boolean[] row : g.pixels()) {
            for (boolean cell : row) {
                anyInk |= cell;
            }
        }
        assertTrue(anyInk, "rendered glyph must not be entirely blank");
    }

    @Test
    void glyphsDelegatesAsciiToFont5x7() {
        Glyph a = Glyphs.glyphFor('A');
        assertEquals(5, a.width());
        assertEquals(7, a.height());
    }

    @Test
    void anUncoveredCodePointFailsInsteadOfDrawingTheMissingGlyphBox() {
        // U+E000 is in the Private Use Area: no real font covers it, so this must throw rather
        // than return the JVM's tofu rectangle (which at block scale reads as intentional lettering).
        assertThrows(FontRenderException.class, () -> Glyphs.glyphFor(0xE000));
    }
}
