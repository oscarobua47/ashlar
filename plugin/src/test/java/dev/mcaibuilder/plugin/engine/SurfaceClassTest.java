// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.mcaibuilder.plugin.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link SurfaceClass} (docs/prompts/step4g-prompt.md, Bug
 * 1): plain material-name strings, no Bukkit needed. Covers the exact
 * regression this fix targets - a tree canopy must classify as vegetation,
 * never liquid.
 */
class SurfaceClassTest {

    @Test
    void waterAndLavaAreLiquid() {
        assertEquals(SurfaceClass.LIQUID, SurfaceClass.classify("WATER", false));
        assertEquals(SurfaceClass.LIQUID, SurfaceClass.classify("LAVA", false));
        assertEquals(SurfaceClass.LIQUID, SurfaceClass.classify("BUBBLE_COLUMN", false));
    }

    @Test
    void plantsSittingOnWaterAreLiquid() {
        assertEquals(SurfaceClass.LIQUID, SurfaceClass.classify("KELP", false));
        assertEquals(SurfaceClass.LIQUID, SurfaceClass.classify("KELP_PLANT", false));
        assertEquals(SurfaceClass.LIQUID, SurfaceClass.classify("SEAGRASS", false));
        assertEquals(SurfaceClass.LIQUID, SurfaceClass.classify("TALL_SEAGRASS", false));
        assertEquals(SurfaceClass.LIQUID, SurfaceClass.classify("LILY_PAD", false));
    }

    @Test
    void waterloggedFlagOverridesToLiquidRegardlessOfMaterial() {
        assertEquals(SurfaceClass.LIQUID, SurfaceClass.classify("OAK_FENCE", true));
        assertEquals(SurfaceClass.LIQUID, SurfaceClass.classify("OAK_LOG", true), "waterlogged wins even over vegetation");
    }

    @Test
    void treeMaterialsAreVegetationNotLiquid() {
        assertEquals(SurfaceClass.VEGETATION, SurfaceClass.classify("OAK_LOG", false));
        assertEquals(SurfaceClass.VEGETATION, SurfaceClass.classify("SPRUCE_WOOD", false));
        assertEquals(SurfaceClass.VEGETATION, SurfaceClass.classify("OAK_LEAVES", false));
        assertEquals(SurfaceClass.VEGETATION, SurfaceClass.classify("AZALEA_LEAVES", false));
        assertEquals(SurfaceClass.VEGETATION, SurfaceClass.classify("MUSHROOM_STEM", false));
        assertEquals(SurfaceClass.VEGETATION, SurfaceClass.classify("CRIMSON_STEM", false));
        assertEquals(SurfaceClass.VEGETATION, SurfaceClass.classify("WARPED_STEM", false));
    }

    @Test
    void otherVegetationMaterialsAreVegetation() {
        assertEquals(SurfaceClass.VEGETATION, SurfaceClass.classify("BAMBOO", false));
        assertEquals(SurfaceClass.VEGETATION, SurfaceClass.classify("CACTUS", false));
        assertEquals(SurfaceClass.VEGETATION, SurfaceClass.classify("CHORUS_PLANT", false));
        assertEquals(SurfaceClass.VEGETATION, SurfaceClass.classify("CHORUS_FLOWER", false));
        assertEquals(SurfaceClass.VEGETATION, SurfaceClass.classify("MANGROVE_ROOTS", false));
        assertEquals(SurfaceClass.VEGETATION, SurfaceClass.classify("AZALEA", false));
        assertEquals(SurfaceClass.VEGETATION, SurfaceClass.classify("FLOWERING_AZALEA", false));
    }

    @Test
    void ordinaryGroundMaterialsAreGround() {
        assertEquals(SurfaceClass.GROUND, SurfaceClass.classify("GRASS_BLOCK", false));
        assertEquals(SurfaceClass.GROUND, SurfaceClass.classify("STONE", false));
        assertEquals(SurfaceClass.GROUND, SurfaceClass.classify("SAND", false));
        assertEquals(SurfaceClass.GROUND, SurfaceClass.classify("SNOW", false), "snow layers are ground, not vegetation");
    }

    @Test
    void classifyIsCaseInsensitive() {
        assertEquals(SurfaceClass.LIQUID, SurfaceClass.classify("water", false));
        assertEquals(SurfaceClass.VEGETATION, SurfaceClass.classify("oak_log", false));
    }

    @Test
    void codesMatchTheSpecOrdering() {
        assertEquals(0, SurfaceClass.GROUND.code());
        assertEquals(1, SurfaceClass.LIQUID.code());
        assertEquals(2, SurfaceClass.VEGETATION.code());
    }
}
