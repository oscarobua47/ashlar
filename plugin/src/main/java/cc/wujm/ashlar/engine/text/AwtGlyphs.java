// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine.text;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Renders one non-ASCII code point (CJK etc.) through {@code java.awt} ({@link BufferedImage} +
 * {@link Graphics2D}), for callers of {@link Glyphs#glyphFor} that fall outside {@link Font5x7}'s
 * printable-ASCII range. The plugin already renders PNGs with {@code java.awt.image} (mc_render),
 * so headless AWT is already known to work in this process.
 *
 * <p>Renders at a font size chosen so the ascent is {@link #HEIGHT} (12) px, thresholds to a
 * bitmap, trims to the glyph's actual ink bounding box, then resamples it into a 12-row cell (a
 * cell lights up when at least {@link #COVERAGE} of its source pixels are ink) capped at {@link
 * #MAX_WIDTH} columns so a single wide CJK character does not become a wall. 12 rows rather than
 * the ASCII font's 7: at 7 a character like "welcome" in Chinese collapses into a solid blob
 * (verified on the test server, step8k); {@link TextLayout} bottom-aligns mixed-height glyphs. Guarded per step8k-prompt.md &sect;A: if this JVM has no fonts
 * ({@link Font} creation/measurement throws, {@link Font#canDisplay} says the code point is not
 * covered, or the rendered bitmap comes out entirely blank), this throws {@link
 * FontRenderException} rather than returning a blank glyph or the JVM's missing-glyph box.
 */
final class AwtGlyphs {

    /** Rows of an AWT-rendered glyph cell: 7 is far too coarse for a CJK character (it turns into a blob), 12 keeps strokes apart. */
    static final int HEIGHT = 12;

    /** Widest a single AWT-rendered glyph cell is allowed to be, in columns. */
    static final int MAX_WIDTH = 12;

    /** Fraction of a cell's oversampled pixels that must be ink for the cell to light up (an "any ink" rule fattens every stroke). */
    private static final double COVERAGE = 0.4;

    /** Oversampled render size used to get clean thresholding before downscaling to the target cell. */
    private static final int RENDER_SCALE = 4;
    private static final int RENDER_SIZE = HEIGHT * RENDER_SCALE * 2;

    private AwtGlyphs() {
    }

    static Glyph glyphFor(int codePoint) {
        String text = new String(Character.toChars(codePoint));
        BufferedImage canvas;
        Font font;
        FontMetrics metrics;
        try {
            canvas = new BufferedImage(RENDER_SIZE, RENDER_SIZE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D probe = canvas.createGraphics();
            try {
                font = new Font(Font.SANS_SERIF, Font.PLAIN, RENDER_SIZE);
                metrics = probe.getFontMetrics(font);
                // Scale the font so its cap height (ascent, a reasonable proxy) lands on the
                // target 7px cap height once downscaled by RENDER_SCALE.
                int ascent = metrics.getAscent();
                if (ascent <= 0) {
                    throw new FontRenderException(noFontsMessage());
                }
                float targetAscent = HEIGHT * RENDER_SCALE;
                float size = font.getSize2D() * (targetAscent / ascent);
                if (!(size > 0) || Float.isNaN(size) || Float.isInfinite(size)) {
                    throw new FontRenderException(noFontsMessage());
                }
                font = font.deriveFont(size);
                if (!font.canDisplay(codePoint)) {
                    // A logical font is a composite: canDisplay is false only when no font in it
                    // covers this code point. Without the check the JVM happily draws its
                    // missing-glyph box, which at block scale looks like a deliberate rectangle
                    // (reported from a real server, 2026-09-22) instead of an error.
                    throw new FontRenderException(noFontsMessage());
                }
                metrics = probe.getFontMetrics(font);
            } finally {
                probe.dispose();
            }
        } catch (FontRenderException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new FontRenderException(noFontsMessage());
        }

        int textWidth;
        int ascent;
        int descent;
        try {
            textWidth = metrics.stringWidth(text);
            ascent = metrics.getAscent();
            descent = metrics.getDescent();
        } catch (RuntimeException e) {
            throw new FontRenderException(noFontsMessage());
        }
        if (textWidth <= 0) {
            throw new FontRenderException(noFontsMessage());
        }

        int imgW = Math.max(1, textWidth + RENDER_SCALE * 4);
        int imgH = Math.max(1, ascent + descent + RENDER_SCALE * 4);
        BufferedImage img = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gfx = img.createGraphics();
        try {
            gfx.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            gfx.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            gfx.setColor(Color.BLACK);
            gfx.fillRect(0, 0, imgW, imgH);
            gfx.setFont(font);
            gfx.setColor(Color.WHITE);
            int baselineY = RENDER_SCALE * 2 + ascent;
            gfx.drawString(text, RENDER_SCALE * 2, baselineY);
        } finally {
            gfx.dispose();
        }

        boolean[][] raw = threshold(img);
        int[] box = inkBoundingBox(raw);
        if (box == null) {
            throw new FontRenderException(noFontsMessage());
        }
        return downscaleToCell(raw, box);
    }

    private static boolean[][] threshold(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        boolean[][] out = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                int r = (argb >> 16) & 0xFF;
                int gC = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                int a = (argb >>> 24) & 0xFF;
                int lum = (r + gC + b) / 3;
                out[y][x] = a > 32 && lum > 96;
            }
        }
        return out;
    }

    /** {minRow, minCol, maxRow, maxCol} (inclusive) of the ink, or null when nothing is lit. */
    private static int[] inkBoundingBox(boolean[][] raw) {
        int minRow = Integer.MAX_VALUE, minCol = Integer.MAX_VALUE;
        int maxRow = -1, maxCol = -1;
        for (int y = 0; y < raw.length; y++) {
            for (int x = 0; x < raw[y].length; x++) {
                if (raw[y][x]) {
                    if (y < minRow) minRow = y;
                    if (y > maxRow) maxRow = y;
                    if (x < minCol) minCol = x;
                    if (x > maxCol) maxCol = x;
                }
            }
        }
        if (maxRow < 0) {
            return null;
        }
        return new int[] {minRow, minCol, maxRow, maxCol};
    }

    /** Downscales the ink bounding box into a {@link #HEIGHT}-row cell, width capped at {@link #MAX_WIDTH}. */
    private static Glyph downscaleToCell(boolean[][] raw, int[] box) {
        int minRow = box[0], minCol = box[1], maxRow = box[2], maxCol = box[3];
        int inkH = maxRow - minRow + 1;
        int inkW = maxCol - minCol + 1;

        int height = HEIGHT;
        double colScale = (double) inkW / inkH * height;
        int width = Math.max(1, Math.min(MAX_WIDTH, (int) Math.round(colScale)));

        boolean[][] pixels = new boolean[height][width];
        for (int r = 0; r < height; r++) {
            int srcY0 = minRow + (int) Math.floor((double) r * inkH / height);
            int srcY1 = minRow + (int) Math.floor((double) (r + 1) * inkH / height);
            srcY1 = Math.max(srcY1, srcY0 + 1);
            for (int c = 0; c < width; c++) {
                int srcX0 = minCol + (int) Math.floor((double) c * inkW / width);
                int srcX1 = minCol + (int) Math.floor((double) (c + 1) * inkW / width);
                srcX1 = Math.max(srcX1, srcX0 + 1);
                pixels[r][c] = inkCoverage(raw, srcY0, Math.min(srcY1, maxRow + 1), srcX0, Math.min(srcX1, maxCol + 1)) >= COVERAGE;
            }
        }
        return new Glyph(width, height, pixels);
    }

    private static double inkCoverage(boolean[][] raw, int y0, int y1, int x0, int x1) {
        int total = 0;
        int lit = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                total++;
                if (raw[y][x]) {
                    lit++;
                }
            }
        }
        return total == 0 ? 0 : (double) lit / total;
    }

    private static String noFontsMessage() {
        return "this server's Java has no font for that character (install a CJK font on the server,"
                + " e.g. fonts-noto-cjk, or use ASCII text)";
    }
}
