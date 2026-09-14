// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.engine;

import net.rcwalter.ashlar.rpc.ErrorCode;
import net.rcwalter.ashlar.rpc.RpcError;

import java.util.Locale;

/**
 * How a {@code fill_batch} operation treats each cell in its region
 * (spec &sect;3.3). {@code REPLACE} is the default when a request omits
 * {@code mode}.
 */
public enum FillMode {
    /** Unconditionally write {@code block} to every cell. */
    REPLACE,
    /** Only write {@code block} where the current block is air. */
    KEEP,
    /** Only write {@code block} to cells on the region's single-layer shell; interior is untouched. */
    OUTLINE,
    /** Write {@code block} on the shell and air in the interior. */
    HOLLOW,
    /**
     * Write {@code block} only on the four vertical sides (x == minX || x ==
     * maxX || z == minZ || z == maxZ), for every y in the region; the
     * interior (including the floor and ceiling layers) is left untouched,
     * unlike {@link #HOLLOW} which also clears the interior to air and, by
     * sealing the top/bottom faces too, would flatten a room's floor and
     * ceiling into solid blocks. Use {@code walls} for rooms and buildings,
     * then add a floor and a roof with separate fills.
     */
    WALLS;

    /** Case-insensitive parse; blank/absent means the default ({@link #REPLACE}). */
    public static FillMode fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return REPLACE;
        }
        try {
            return FillMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RpcError(ErrorCode.BAD_REQUEST, "unknown fill mode: '" + raw + "'");
        }
    }
}
