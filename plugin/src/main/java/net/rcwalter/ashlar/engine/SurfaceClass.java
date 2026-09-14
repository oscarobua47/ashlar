// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.engine;

import java.util.Set;

/**
 * Classifies a heightmap cell's surface block for {@code heightmap} and the
 * {@code render} {@code view: "heightmap"} (docs/prompts/step4g-prompt.md,
 * Bug 1): the previous "requested type vs SOLID height differ" liquid test
 * also fired under every tree canopy (leaves count for {@code OCEAN_FLOOR}
 * but not for {@code MOTION_BLOCKING_NO_LEAVES}), painting forests as lakes.
 * Classification now looks at the surface block's own material instead.
 *
 * <p>{@link #classify} takes a material name (and waterlogged flag) as a
 * plain {@code String}/{@code boolean} rather than a Bukkit {@code
 * BlockData}, so this class has no Bukkit dependency and is unit-testable
 * without a server; callers ({@link HeightmapTask}, {@link
 * HeightmapImageTask}) extract those two facts from the real block.
 */
public enum SurfaceClass {
    GROUND(0),
    LIQUID(1),
    VEGETATION(2);

    private final int code;

    SurfaceClass(int code) {
        this.code = code;
    }

    /** The {@code classes} JSON value for this class (spec: 0 ground, 1 liquid, 2 vegetation). */
    public int code() {
        return code;
    }

    /**
     * Materials that are liquid surfaces outright: still/flowing water and
     * lava, bubble columns, and the handful of plants/blocks that only ever
     * sit on top of water (kelp, seagrass, lily pads) - their presence means
     * the cell is water even though the plant block itself is not a fluid.
     */
    private static final Set<String> LIQUID_MATERIALS = Set.of(
            "WATER", "LAVA", "BUBBLE_COLUMN",
            "KELP", "KELP_PLANT", "SEAGRASS", "TALL_SEAGRASS", "LILY_PAD"
    );

    /** Vegetation materials whose name does not end in one of the checked suffixes. */
    private static final Set<String> VEGETATION_MATERIALS = Set.of(
            "BAMBOO", "CACTUS", "CHORUS_PLANT", "CHORUS_FLOWER",
            "MANGROVE_ROOTS", "AZALEA", "FLOWERING_AZALEA"
    );

    /**
     * Classifies a surface block. {@code waterlogged} is {@code true} when
     * the block's {@code BlockData} implements {@code
     * org.bukkit.block.data.Waterlogged} and {@code isWaterlogged()} is
     * true; callers pass {@code false} for materials that cannot be
     * waterlogged.
     */
    public static SurfaceClass classify(String materialName, boolean waterlogged) {
        String name = materialName.toUpperCase(java.util.Locale.ROOT);
        if (waterlogged || LIQUID_MATERIALS.contains(name)) {
            return LIQUID;
        }
        if (name.endsWith("_LOG") || name.endsWith("_WOOD") || name.endsWith("_LEAVES") || name.endsWith("_STEM")
                || VEGETATION_MATERIALS.contains(name)) {
            return VEGETATION;
        }
        return GROUND;
    }
}
