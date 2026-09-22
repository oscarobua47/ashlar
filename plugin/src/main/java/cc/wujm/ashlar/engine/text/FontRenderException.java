// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine.text;

/**
 * Thrown by {@link AwtGlyphs#glyphFor} when this JVM cannot render a non-ASCII code point (no
 * fonts available, or the rendered bitmap came out blank). {@link #getMessage()} is the exact
 * client-facing text step8k-prompt.md &sect;A requires; the {@code mc_build} layer wraps this into
 * a {@code ToolArgError} with the same message rather than silently producing a blank glyph.
 */
public final class FontRenderException extends RuntimeException {

    public FontRenderException(String message) {
        super(message);
    }
}
