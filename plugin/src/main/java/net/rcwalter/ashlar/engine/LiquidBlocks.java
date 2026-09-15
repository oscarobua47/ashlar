// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.engine;

import org.bukkit.Material;

/**
 * Which materials count as "liquid" for {@code liquids: "flow"} (step8d-prompt.md): a target
 * block written with {@code setBlockData(data, true)} instead of the usual {@code false}, so
 * vanilla fluid physics schedules its spread ticks - confirmed on the local Paper 26.2 test
 * server before this class was written (see the step8d report): a water/lava source placed 3
 * blocks above a floor with {@code applyPhysics=true} produced a falling column and a spreading
 * pool within a few seconds, while the same placement with {@code applyPhysics=false} (the
 * engine's normal behaviour) stayed exactly as placed.
 *
 * <p>Deliberately narrower than {@link ConnectionPass}'s private {@code isLiquid} check, which
 * also treats {@link Material#BUBBLE_COLUMN} as a liquid so its air-then-back refresh never
 * touches one; bubble columns are not liquids for this feature (step8d-prompt.md) and are always
 * written with {@code physics=false}, like every other non-fluid block. Waterlogged blocks (a
 * fence, a slab, ...) are not liquids either: their {@link Material} is the fence/slab, never
 * {@code WATER}, so they fall through to {@code physics=false} without any extra check here.
 */
final class LiquidBlocks {

    private LiquidBlocks() {
    }

    /** Whether {@code material} is one of the two fluids that spread under vanilla physics. */
    static boolean isFlowable(Material material) {
        return material == Material.WATER || material == Material.LAVA;
    }

    /**
     * Same test as {@link #isFlowable}, but worked out from a raw {@code "minecraft:..."} block
     * state string instead of a parsed {@link Material} - used by {@link RequestValidator} to
     * count flowing-liquid blocks against {@code limits.max-flowing-liquids-per-operation} before
     * the block string has been parsed into {@link org.bukkit.block.data.BlockData} (or even when
     * it never needs to be, e.g. a rejected op). This keeps the counting/cap logic pure Java with
     * no {@link org.bukkit.Bukkit#createBlockData} call, and so directly unit-testable
     * (RequestValidatorTest, step8d-prompt.md) without a live Paper block registry.
     *
     * <p>Every block string this codebase accepts is namespaced ({@code mc_build.json}: "Block
     * state string in the \"minecraft:\" namespace"), so comparing the id before any {@code '['}
     * to exactly {@code "minecraft:water"}/{@code "minecraft:lava"} matches {@link #isFlowable}
     * for every string {@link BlockDataParser} can parse. A waterlogged block (a fence, a slab,
     * ...) has some other id before its {@code [waterlogged=true]} suffix, so it correctly never
     * counts here either.
     */
    static boolean isFlowableBlockString(String raw) {
        if (raw == null) {
            return false;
        }
        int bracket = raw.indexOf('[');
        String id = bracket >= 0 ? raw.substring(0, bracket) : raw;
        return id.equals("minecraft:water") || id.equals("minecraft:lava");
    }
}
