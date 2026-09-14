// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.mcaibuilder.plugin.engine;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.Rail;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Ladder;
import org.bukkit.block.data.type.Sign;
import org.bukkit.block.data.type.Snow;
import org.bukkit.block.data.type.Switch;
import org.bukkit.block.data.type.TripwireHook;
import org.bukkit.block.data.type.WallSign;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Post-pass (docs/prompts/step4h-prompt.md) that reports blocks left without
 * the support they would need in vanilla physics: since {@code fill_batch}/
 * {@code set_blocks}/{@code restore} write with {@code physics=false} (never
 * triggers block-update/pop-off), an AI can leave a ladder with nothing
 * behind it or a torch buried inside a wall and nothing tells it - the block
 * simply stays where it was put. This pass re-examines every written
 * position whose block *would* need support in real Minecraft and records a
 * warning (never fixes anything; purely diagnostic), so the caller sees the
 * mistake in the RPC result instead of only discovering it by eye later.
 *
 * <p>Runs after {@link ConnectionPass} in the owning task's {@code step()},
 * budgeted through the same tick-deadline mechanism (main thread only, per
 * plan.md &sect;2.3 point 1) - by the time it runs, every write in the batch
 * (including any later op that overwrote an earlier one) has already landed,
 * so reading each candidate position's current block is authoritative.
 *
 * <p><b>Rules verified against {@link Material#isSolid()} on the local Paper
 * test server (docs/prompts/step4h-prompt.md verification step) before being
 * finalized:</b> full opaque blocks (stone, dirt, planks, ...) and most
 * partial-but-load-bearing blocks (slabs, stairs when used as a base -
 * irrelevant here since this pass only checks the small set of shapes below)
 * report {@code isSolid() == true}; glass panes, fences/walls (thin
 * "connectable" shapes), and every shape this class itself checks (ladders,
 * torches, signs, carpets, pressure plates, rails, snow layers, doors) all
 * report {@code isSolid() == false}, matching vanilla's own support rules
 * closely enough for this diagnostic (a false positive - flagging a block
 * that vanilla would actually accept - is far less costly than a silent
 * miss, since every warning is advisory, never blocking the RPC result).
 */
final class SupportCheck {

    /** One flagged block: exact position, its current block-state string, and why it needs support. */
    record Warning(int x, int y, int z, String block, String reason) {
    }

    /** How many candidate positions are examined between deadline checks. */
    private static final int DEADLINE_CHECK_INTERVAL = 128;

    /** Warnings are capped per task; {@link #truncated()} reports whether more were found beyond the cap. */
    private static final int MAX_WARNINGS = 50;

    // A compact, deliberately non-exhaustive list of common decorative plants that need a solid
    // block below them (step4h-prompt.md: "keep it a compact list ... do not try to be exhaustive").
    private static final Set<Material> DECORATIVE_PLANTS = EnumSet.of(
            Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN,
            Material.DANDELION, Material.POPPY, Material.BLUE_ORCHID, Material.ALLIUM,
            Material.AZURE_BLUET, Material.OXEYE_DAISY, Material.CORNFLOWER,
            Material.LILY_OF_THE_VALLEY, Material.WITHER_ROSE, Material.SUNFLOWER,
            Material.LILAC, Material.ROSE_BUSH, Material.PEONY);

    private final World world;
    private final List<int[]> positions;
    private final boolean enabled;
    private final List<Warning> warnings = new ArrayList<>();
    private final Set<Long> seen = new HashSet<>();

    private boolean truncated = false;
    private int index = 0;

    SupportCheck(World world, List<int[]> positions, boolean enabled) {
        this.world = world;
        this.positions = positions;
        this.enabled = enabled;
    }

    /**
     * Whether a freshly written block's data is one this pass ever checks. Callers use this at
     * write time (mirroring {@link ConnectionPass#isConnectable}) so the candidate list stays
     * proportional to how many support-sensitive blocks a batch actually places, not its size.
     */
    static boolean needsCheck(BlockData data) {
        return data instanceof Switch || wallAttachedFacing(data) != null || needsAbove(data) || needsBelow(data);
    }

    /** Does as much work as fits before {@code deadlineNanos}; returns {@code true} once fully done. */
    boolean step(long deadlineNanos) {
        if (!enabled) {
            return true;
        }
        int sinceCheck = 0;
        while (index < positions.size()) {
            if (warnings.size() >= MAX_WARNINGS) {
                truncated = true;
                index = positions.size();
                break;
            }
            int[] pos = positions.get(index);
            checkOne(pos[0], pos[1], pos[2]);
            index++;
            sinceCheck++;
            if (sinceCheck >= DEADLINE_CHECK_INTERVAL) {
                sinceCheck = 0;
                if (index < positions.size() && System.nanoTime() >= deadlineNanos) {
                    return false;
                }
            }
        }
        return true;
    }

    List<Warning> warnings() {
        return warnings;
    }

    boolean truncated() {
        return truncated;
    }

    private void checkOne(int x, int y, int z) {
        // Two ops in the same batch can write the same coordinate; only the last write matters,
        // and re-checking it twice would otherwise emit a duplicate warning.
        long key = (((long) (x & 0x3FFFFFF)) << 38) | (((long) (y & 0xFFF)) << 26) | (z & 0x3FFFFFF);
        if (!seen.add(key)) {
            return;
        }
        BlockData data = world.getBlockAt(x, y, z).getBlockData();
        Warning w = evaluate(x, y, z, data);
        if (w != null) {
            warnings.add(w);
        }
    }

    private Warning evaluate(int x, int y, int z, BlockData data) {
        if (data instanceof Switch sw) {
            return switch (sw.getAttachedFace()) {
                case WALL -> checkWallAttached(x, y, z, data, sw.getFacing());
                case FLOOR -> checkBelow(x, y, z, data);
                case CEILING -> checkAbove(x, y, z, data);
            };
        }
        BlockFace wallFacing = wallAttachedFacing(data);
        if (wallFacing != null) {
            return checkWallAttached(x, y, z, data, wallFacing);
        }
        if (needsAbove(data)) {
            return checkAbove(x, y, z, data);
        }
        if (needsBelow(data)) {
            return checkBelow(x, y, z, data);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Rule 1: attached to a wall - facing points away from the wall, so the
    // supporting block sits at pos - facing.
    // ------------------------------------------------------------------

    private static BlockFace wallAttachedFacing(BlockData data) {
        if (!(data instanceof Directional)) {
            return null;
        }
        Material m = data.getMaterial();
        boolean wallAttached = data instanceof Ladder
                || data instanceof WallSign // WallHangingSign is deliberately not included: handled by needsAbove.
                || data instanceof TripwireHook
                || isWallTorch(m)
                || isWallBanner(m);
        return wallAttached ? ((Directional) data).getFacing() : null;
    }

    private Warning checkWallAttached(int x, int y, int z, BlockData data, BlockFace facing) {
        int nx = x - facing.getModX();
        int ny = y - facing.getModY();
        int nz = z - facing.getModZ();
        if (isSolid(nx, ny, nz)) {
            return null;
        }
        String reason = "no support behind (facing=" + facing.name().toLowerCase(Locale.ROOT)
                + " needs a solid block at " + axisExpr(facing) + ")";
        return new Warning(x, y, z, data.getAsString(), reason);
    }

    /** e.g. facing=EAST (modX=1) means the wall is one block west, i.e. at this block's x-1. */
    private static String axisExpr(BlockFace facing) {
        int mx = facing.getModX();
        int my = facing.getModY();
        int mz = facing.getModZ();
        if (mx != 0) {
            return "x" + (mx > 0 ? "-1" : "+1");
        }
        if (mz != 0) {
            return "z" + (mz > 0 ? "-1" : "+1");
        }
        return "y" + (my > 0 ? "-1" : "+1");
    }

    private static boolean isWallTorch(Material m) {
        return m == Material.WALL_TORCH || m == Material.SOUL_WALL_TORCH || m == Material.REDSTONE_WALL_TORCH;
    }

    private static boolean isWallBanner(Material m) {
        return m.name().endsWith("_WALL_BANNER");
    }

    // ------------------------------------------------------------------
    // Rule 3: needs a block above (hanging signs, hanging lanterns; chains
    // are deliberately skipped per step4h-prompt.md).
    // ------------------------------------------------------------------

    private static boolean needsAbove(BlockData data) {
        Material m = data.getMaterial();
        if (m.name().endsWith("_HANGING_SIGN")) {
            return true; // covers both HangingSign and WallHangingSign block data.
        }
        return data instanceof Lantern lantern && lantern.isHanging();
    }

    private Warning checkAbove(int x, int y, int z, BlockData data) {
        if (isSolid(x, y + 1, z)) {
            return null;
        }
        return new Warning(x, y, z, data.getAsString(), "nothing solid above");
    }

    // ------------------------------------------------------------------
    // Rule 2: needs a block below, plus the "embedded standing torch" special
    // case (Rule 4) checked only once the block below is confirmed solid.
    // ------------------------------------------------------------------

    private static boolean needsBelow(BlockData data) {
        // The upper half of any two-tall block (a door, or a two-tall plant such as tall_grass/
        // large_fern/sunflower/lilac/rose_bush/peony - Bukkit exposes no dedicated "two-tall plant"
        // interface, only the shared Bisected one) rests on its own lower half, not on the ground;
        // checking it here would false-positive every such block's top half (verified on the local
        // test server: a restored tall_grass[half=upper] read back with nothing solid below it, by
        // design - its lower half is the support, and that half is itself never solid).
        if (data instanceof Bisected bisected && bisected.getHalf() == Bisected.Half.TOP) {
            return false;
        }
        Material m = data.getMaterial();
        if (isStandingTorch(m)) {
            return true;
        }
        if (data instanceof Sign) {
            return true; // standing sign only; WallSign is a distinct interface handled by Rule 1.
        }
        if (m.name().endsWith("_BANNER") && !isWallBanner(m)) {
            return true; // standing banner.
        }
        if (data instanceof Door) {
            return true; // half==TOP already returned above, so reaching here means half==BOTTOM.
        }
        if (m.name().endsWith("_CARPET") || m.name().endsWith("_PRESSURE_PLATE")) {
            return true;
        }
        if (data instanceof Rail || data instanceof Snow) {
            return true;
        }
        if (data instanceof Lantern lantern) {
            return !lantern.isHanging(); // a floor lantern; the hanging case is Rule 3.
        }
        return isDecorativePlant(m);
    }

    private static boolean isDecorativePlant(Material m) {
        if (m.isSolid()) {
            return false;
        }
        return m.name().endsWith("_SAPLING") || m.name().endsWith("_TULIP") || DECORATIVE_PLANTS.contains(m);
    }

    private static boolean isStandingTorch(Material m) {
        return m == Material.TORCH || m == Material.SOUL_TORCH || m == Material.REDSTONE_TORCH;
    }

    private Warning checkBelow(int x, int y, int z, BlockData data) {
        if (!isSolid(x, y - 1, z)) {
            return new Warning(x, y, z, data.getAsString(), "nothing solid below");
        }
        if (isStandingTorch(data.getMaterial()) && isEmbedded(x, y, z)) {
            return new Warning(x, y, z, data.getAsString(), "embedded");
        }
        return null;
    }

    /** Rule 4: a standing torch is almost certainly a "wall_torch replaced a wall block" mistake. */
    private boolean isEmbedded(int x, int y, int z) {
        int solidSides = 0;
        if (isSolid(x + 1, y, z)) solidSides++;
        if (isSolid(x - 1, y, z)) solidSides++;
        if (isSolid(x, y, z + 1)) solidSides++;
        if (isSolid(x, y, z - 1)) solidSides++;
        return solidSides >= 3;
    }

    private boolean isSolid(int x, int y, int z) {
        return world.getBlockAt(x, y, z).getType().isSolid();
    }
}
