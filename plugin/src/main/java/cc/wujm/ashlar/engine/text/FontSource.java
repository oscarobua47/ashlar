// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine.text;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Holds the base {@link Font} {@link AwtGlyphs} derives non-ASCII (CJK etc.) glyphs from, driven
 * by the hot {@code engine.text-font-file} config key (step8n-prompt.md). Empty (the default)
 * means "no font file configured": {@link #font()} then returns {@code null} and {@link
 * AwtGlyphs} falls back to the JVM's logical SANS_SERIF, exactly as before this step.
 *
 * <p>A plain static holder, same pattern as {@link cc.wujm.ashlar.rpc.MainThread}: {@link
 * #configure} is called once at plugin startup and again on every successful {@code /ashlar
 * reload} (both main-thread only, never concurrently with each other - guarded anyway since it is
 * cheap); {@link #font()} is read from any thread ({@code text} entries are expanded on the
 * network thread, and several requests can render at once), which the plain volatile fields cover
 * - a {@link Font} is itself immutable once constructed, so publishing a new one via a volatile
 * write is all a reader needs.
 *
 * <p>{@link #configure} only re-reads the file when the configured *path string* changes, not
 * when the file at that path changes on disk: comparing file content or an mtime on every reload
 * would add a disk read plus a font parse (not cheap) to every {@code /ashlar reload} regardless
 * of whether this key changed at all, for a case (editing the file without touching the path)
 * that is rare and easy to work around. A font file replaced at the same path therefore is not
 * picked up by {@code /ashlar reload} alone - either restart the server, or reload twice with the
 * path pointed elsewhere (or blanked) in between so the path string itself changes and the cache
 * invalidates. Documented in {@code config.yml} and the README troubleshooting section.
 */
public final class FontSource {

    private static final Object LOCK = new Object();

    private static volatile String configuredPath = "";
    private static volatile Font cachedFont;

    private FontSource() {
    }

    /**
     * Applies a (possibly unchanged) {@code engine.text-font-file} value. {@code dataFolder} is
     * the plugin's data folder ({@code plugins/Ashlar/}), used to resolve a relative path;
     * {@code null} is only for tests that always pass an absolute path or an empty string. Never
     * throws: a bad path just logs a warning and leaves {@link #font()} returning {@code null}
     * (system font fallback).
     */
    public static void configure(String path, Path dataFolder, Logger logger) {
        String normalized = path == null ? "" : path.trim();
        synchronized (LOCK) {
            if (normalized.equals(configuredPath)) {
                return;
            }
            configuredPath = normalized;
            cachedFont = load(normalized, dataFolder, logger);
        }
    }

    /** The configured font, or {@code null} when none is configured or it failed to load (caller falls back to SANS_SERIF). */
    public static Font font() {
        return cachedFont;
    }

    /** Pure loading logic, directly unit-testable: never throws, returns {@code null} on any failure. */
    static Font load(String path, Path dataFolder, Logger logger) {
        if (path.isEmpty()) {
            return null;
        }
        File file = resolve(path, dataFolder);
        if (!file.isFile()) {
            logger.warning("engine.text-font-file '" + path + "' does not exist or is not a regular file;"
                    + " falling back to the system font for non-ASCII lettering.");
            return null;
        }
        try {
            return Font.createFont(Font.TRUETYPE_FONT, file);
        } catch (IOException | FontFormatException | RuntimeException e) {
            logger.warning("engine.text-font-file '" + path + "' could not be loaded (" + e.getMessage()
                    + "); falling back to the system font for non-ASCII lettering.");
            return null;
        }
    }

    private static File resolve(String path, Path dataFolder) {
        File direct = new File(path);
        if (direct.isAbsolute() || dataFolder == null) {
            return direct;
        }
        return dataFolder.resolve(path).toFile();
    }
}
