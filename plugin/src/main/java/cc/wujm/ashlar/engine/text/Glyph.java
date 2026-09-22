// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine.text;

/**
 * One rendered character cell: {@code width}x{@code height} pixels, {@code pixels[row][col]}
 * true where ink is present. {@code row} 0 is the top of the cell, {@code col} 0 is the left.
 * Produced by {@link Font5x7#glyphFor} (ASCII) or {@link AwtGlyphs#glyphFor} (everything else).
 */
public record Glyph(int width, int height, boolean[][] pixels) {
}
