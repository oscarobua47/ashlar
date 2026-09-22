// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Font5x7} (step8k-prompt.md &sect;C): prints the full 95-glyph ASCII sheet
 * (rows of 16, one glyph per {@code #}/{@code .} block) to test output for visual review, and
 * asserts a few known shapes so a scrambled glyph table fails loudly.
 */
class Font5x7Test {

    @Test
    void printsFullAsciiSheet() {
        StringBuilder sb = new StringBuilder();
        sb.append("Font5x7 ASCII sheet (0x20-0x7E, 95 glyphs, 16 per row):\n");
        int total = Font5x7.LAST_CODE_POINT - Font5x7.FIRST_CODE_POINT + 1;
        for (int rowStart = Font5x7.FIRST_CODE_POINT; rowStart <= Font5x7.LAST_CODE_POINT; rowStart += 16) {
            int rowEnd = Math.min(rowStart + 15, Font5x7.LAST_CODE_POINT);
            Glyph[] glyphs = new Glyph[rowEnd - rowStart + 1];
            for (int cp = rowStart; cp <= rowEnd; cp++) {
                glyphs[cp - rowStart] = Font5x7.glyphFor(cp);
            }
            StringBuilder header = new StringBuilder("  ");
            for (int cp = rowStart; cp <= rowEnd; cp++) {
                char label = (cp == ' ') ? '_' : (char) cp;
                header.append(' ').append(label).append(label).append(label).append(label).append(label).append(' ');
            }
            sb.append(header).append('\n');
            for (int r = 0; r < Font5x7.HEIGHT; r++) {
                sb.append("  ");
                for (Glyph g : glyphs) {
                    sb.append(' ');
                    for (int c = 0; c < Font5x7.WIDTH; c++) {
                        sb.append(g.pixels()[r][c] ? '#' : '.');
                    }
                    sb.append(' ');
                }
                sb.append('\n');
            }
            sb.append('\n');
        }
        System.out.println(sb);
        assertEquals(95, total);
    }

    @Test
    void iIsAVerticalBarWithSerifs() {
        Glyph i = Font5x7.glyphFor('I');
        // top and bottom rows are full serif bars
        assertRow(i, 0, "#####");
        assertRow(i, 6, "#####");
        // middle rows are a single-column vertical stroke
        for (int r = 1; r < 6; r++) {
            assertRow(i, r, "..#..");
        }
    }

    @Test
    void periodIsOnePixelAtTheBottom() {
        Glyph dot = Font5x7.glyphFor('.');
        for (int r = 0; r < 6; r++) {
            assertRow(dot, r, ".....");
        }
        assertRow(dot, 6, "..#..");
    }

    @Test
    void oIsARing() {
        Glyph o = Font5x7.glyphFor('O');
        assertRow(o, 0, ".###.");
        for (int r = 1; r < 6; r++) {
            assertRow(o, r, "#...#");
        }
        assertRow(o, 6, ".###.");
    }

    @Test
    void spaceIsEmpty() {
        Glyph space = Font5x7.glyphFor(' ');
        for (int r = 0; r < Font5x7.HEIGHT; r++) {
            for (int c = 0; c < Font5x7.WIDTH; c++) {
                assertFalse(space.pixels()[r][c], "space must be entirely blank at row " + r + " col " + c);
            }
        }
    }

    @Test
    void everyGlyphIs5x7() {
        for (int cp = Font5x7.FIRST_CODE_POINT; cp <= Font5x7.LAST_CODE_POINT; cp++) {
            Glyph g = Font5x7.glyphFor(cp);
            assertEquals(5, g.width(), "code point " + cp);
            assertEquals(7, g.height(), "code point " + cp);
        }
    }

    @Test
    void supportsOnlyPrintableAscii() {
        assertTrue(Font5x7.supports('A'));
        assertTrue(Font5x7.supports(' '));
        assertTrue(Font5x7.supports('~'));
        assertFalse(Font5x7.supports(0x1F));
        assertFalse(Font5x7.supports(0x7F));
        assertFalse(Font5x7.supports(0x4E2D)); // a CJK code point
    }

    private static void assertRow(Glyph g, int row, String expected) {
        StringBuilder actual = new StringBuilder();
        for (int c = 0; c < g.width(); c++) {
            actual.append(g.pixels()[row][c] ? '#' : '.');
        }
        assertEquals(expected, actual.toString(), "row " + row);
    }
}
