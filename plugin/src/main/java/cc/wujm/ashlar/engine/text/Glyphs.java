// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine.text;

/**
 * Single entry point {@link TextLayout} and {@link TextExpand} use to resolve a code point to a
 * {@link Glyph}: {@link Font5x7} for printable ASCII (0x20-0x7E), {@link AwtGlyphs} for everything
 * else. A non-ASCII code point this JVM cannot render throws {@link FontRenderException}.
 */
public final class Glyphs {

    private Glyphs() {
    }

    public static Glyph glyphFor(int codePoint) {
        if (Font5x7.supports(codePoint)) {
            return Font5x7.glyphFor(codePoint);
        }
        return AwtGlyphs.glyphFor(codePoint);
    }
}
