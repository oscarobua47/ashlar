// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import org.bukkit.Color;
import org.bukkit.block.data.BlockData;

/**
 * Resolves a palette entry's {@code minecraft:id[props]} string (as produced
 * by {@link RegionData}) to the ARGB int of its vanilla map color, for the
 * {@code render} RPC (docs/prompts/step4e-prompt.md). Used only from
 * {@link RenderTask#buildResult}, which runs on the main thread (same
 * thread-safety story as {@link BlockDataParser}: {@link
 * Bukkit#createBlockData} and {@link BlockData#getMapColor()} are pure
 * registry/state lookups, not {@code World} instance calls).
 *
 * <p>Verified against the decompiled Paper 26.2 server jar (plugin/run/versions/26.2/paper-26.2.jar,
 * {@code CraftBlockData#getMapColor}): it returns {@code
 * Color.fromRGB(state.getMapColor(null, null).col)}. Air (and any other
 * block with no assigned map color) uses {@code MapColor.NONE}, constructed
 * as {@code new MapColor(0, 0)} - i.e. {@code col == 0}, which {@code
 * Color.fromRGB} turns into opaque black (RGB 0x000000, default alpha 255).
 * No real vanilla map color is pure black (the darkest entries are dark grays
 * a few units above zero), so "RGB is exactly 0x000000" is a safe, exact
 * signal for "no map color" and is treated as fully transparent (ARGB 0)
 * rather than rendered as black. {@link org.bukkit.Material#isAir()} is
 * checked first as a fast, explicit path for the common case.
 */
public final class MapColorResolver {

    private MapColorResolver() {
    }

    /** ARGB (alpha 0xFF for a real color, 0 for "transparent/no map color") for one palette entry. */
    public static int resolve(String blockString) {
        BlockData data = BlockDataParser.parse(blockString);
        if (data.getMaterial().isAir()) {
            return 0;
        }
        Color color = data.getMapColor();
        if (color == null) {
            return 0;
        }
        int rgb = color.asRGB() & 0xFFFFFF;
        if (rgb == 0) {
            return 0; // MapColor.NONE (col == 0): see class javadoc.
        }
        return 0xFF000000 | rgb;
    }
}
