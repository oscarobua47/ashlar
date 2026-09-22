// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine.text;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure layout pass for {@code mc_build}'s {@code text} entries (step8k-prompt.md &sect;A): turns a
 * (trimmed) string into a list of {@link PlacedGlyph}s plus the overall pixel width/height, without
 * touching Bukkit or rasterizing anything - {@link TextExpand} does the rasterizing/world-coordinate
 * work on top of this. Codepoints are resolved to glyph cells lazily via {@link Glyphs#glyphFor} only
 * to learn each glyph's width (needed for layout); the pixels themselves are read again by {@link
 * TextExpand}.
 */
public final class TextLayout {

    /** Width, in columns, of a literal space character. */
    public static final int SPACE_WIDTH = 3;

    /** Rows between two lines (on top of each line's own rows: 7 for ASCII, taller when it holds a CJK glyph). */
    public static final int LINE_SPACING = 1;

    public static final int MAX_CHARS = 64;
    public static final int MAX_LINES = 4;

    private TextLayout() {
    }

    /**
     * One glyph placed within the overall text block: {@code line} is 0-based (top to bottom),
     * {@code col} is the glyph's leftmost column within that line, {@code row} its topmost pixel row
     * counted from the top of the whole block. Glyphs of different heights on one line (a 7-row ASCII
     * letter next to a 12-row CJK character) sit on the same baseline: the line's bottom row.
     */
    public record PlacedGlyph(int codePoint, int line, int col, int row, int width, int height) {
    }

    /**
     * The full layout: every non-space glyph placed, the overall pixel bounding box (before
     * {@code scale}), and {@code firstLineHeight} - the rows of line 0, which is what an entry's
     * {@code pos} (bottom-left of the first line) is measured against.
     */
    public record Layout(List<PlacedGlyph> glyphs, int width, int height, int lineCount, int firstLineHeight) {
    }

    /**
     * Lays out {@code text} (already expected to be used verbatim - callers trim first so the error
     * message below reports the length the caller actually validated).
     *
     * @throws IllegalArgumentException if {@code text} is empty/too long after trimming, or has more
     *         than {@link #MAX_LINES} lines
     */
    public static Layout layout(String text, int spacing) {
        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_CHARS) {
            throw new IllegalArgumentException(
                    "text must be 1-" + MAX_CHARS + " characters after trimming, got " + trimmed.length());
        }
        String[] lines = trimmed.split("\n", -1);
        if (lines.length > MAX_LINES) {
            throw new IllegalArgumentException("text must have at most " + MAX_LINES + " lines, got " + lines.length);
        }

        // Pass 1: resolve glyphs and each line's height (the tallest glyph on it, at least 7 rows).
        record Pending(int codePoint, int line, int col, Glyph glyph) {
        }
        List<Pending> pending = new ArrayList<>();
        int[] lineHeights = new int[lines.length];
        int maxWidth = 0;
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            int col = 0;
            int lineHeight = Font5x7.HEIGHT;
            String line = lines[lineIndex];
            boolean first = true;
            int codePointCount = line.codePointCount(0, line.length());
            int i = 0;
            for (int k = 0; k < codePointCount; k++) {
                int cp = line.codePointAt(i);
                i += Character.charCount(cp);
                if (!first) {
                    col += spacing;
                }
                first = false;
                if (cp == ' ') {
                    col += SPACE_WIDTH;
                    continue;
                }
                Glyph g = Glyphs.glyphFor(cp);
                pending.add(new Pending(cp, lineIndex, col, g));
                col += g.width();
                lineHeight = Math.max(lineHeight, g.height());
            }
            lineHeights[lineIndex] = lineHeight;
            maxWidth = Math.max(maxWidth, col);
        }

        // Pass 2: absolute rows - lines stack top to bottom, glyphs bottom-align within their line.
        int[] lineTops = new int[lines.length];
        int height = 0;
        for (int l = 0; l < lines.length; l++) {
            lineTops[l] = height;
            height += lineHeights[l] + (l + 1 < lines.length ? LINE_SPACING : 0);
        }
        List<PlacedGlyph> glyphs = new ArrayList<>();
        for (Pending p : pending) {
            int row = lineTops[p.line()] + lineHeights[p.line()] - p.glyph().height();
            glyphs.add(new PlacedGlyph(p.codePoint(), p.line(), p.col(), row, p.glyph().width(), p.glyph().height()));
        }
        return new Layout(glyphs, maxWidth, height, lines.length, lineHeights[0]);
    }
}
