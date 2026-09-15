// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import org.bukkit.DyeColor;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Executes a {@code set_blocks} request: a sparse list of coordinate/block
 * pairs, written in order. Unlike {@link FillTask} there is no "row" concept,
 * so the tick deadline is simply checked after every write.
 *
 * <p>An entry may also carry {@link SignData} (Fix 3,
 * docs/prompts/step4d-prompt.md): after the block write, its block entity
 * text/appearance is set via the {@link Sign} block state API. Once every
 * op is processed, a {@link ConnectionPass} (Fix 2) runs over every written
 * position whose data is connectable (glass panes, fences, walls, stairs,
 * redstone wire, ...), budgeted through the same tick deadline.
 */
public final class SparseTask extends BuildTask {

    private final List<SparseOp> ops;
    private final World world;
    private final List<int[]> connectablePositions = new ArrayList<>();
    private final ConnectionPass connectionPass;
    private final List<int[]> chestPositions = new ArrayList<>();
    private final ChestPairPass chestPairPass;
    private final List<int[]> supportPositions = new ArrayList<>();
    private final NeighbourPositions neighbourPositions = new NeighbourPositions();
    private final SupportCheck supportCheck;
    private final boolean liquidsFlow;
    private int index = 0;
    private int worldMinHeight;
    private int worldMaxHeight;
    private boolean heightsCached = false;

    /**
     * {@code liquidsFlow}: the request's {@code "liquids": "flow"} (step8d-prompt.md); see
     * {@link FillTask#FillTask} for the full rationale.
     */
    public SparseTask(Region region, List<SparseOp> ops, World world, boolean connect, boolean supportWarnings,
            boolean liquidsFlow) {
        super(region);
        this.ops = ops;
        this.world = world;
        this.connectionPass = new ConnectionPass(world, connectablePositions, connect);
        this.chestPairPass = new ChestPairPass(world, chestPositions, connect);
        this.supportCheck = new SupportCheck(world, supportPositions, neighbourPositions.positions(), supportWarnings);
        this.liquidsFlow = liquidsFlow;
    }

    @Override
    public World world() {
        return world;
    }

    @Override
    public long volume() {
        return ops.size();
    }

    @Override
    public boolean step(long deadlineNanos) {
        if (!heightsCached) {
            worldMinHeight = world.getMinHeight();
            worldMaxHeight = world.getMaxHeight();
            heightsCached = true;
        }
        while (index < ops.size()) {
            SparseOp op = ops.get(index);
            BlockData target = op.block();
            Block block = world.getBlockAt(op.x(), op.y(), op.z());
            BlockData current = block.getBlockData();
            boolean blockChanged = !current.equals(target);
            if (blockChanged) {
                // The only block-writing call allowed anywhere: physics is enabled only for a
                // liquid target under liquids:"flow" (step8d-prompt.md); everything else, and
                // every liquid when liquidsFlow is false, is written with physics=false.
                boolean physics = liquidsFlow && LiquidBlocks.isFlowable(target.getMaterial());
                block.setBlockData(target, physics);
                if (physics) {
                    addPhysicsWrite();
                }
                addChanged(1);
                if (ConnectionPass.isConnectable(target)) {
                    connectablePositions.add(new int[]{op.x(), op.y(), op.z()});
                }
                if (ChestPairPass.isChest(target)) {
                    chestPositions.add(new int[]{op.x(), op.y(), op.z()});
                }
                if (SupportCheck.needsCheck(target)) {
                    supportPositions.add(new int[]{op.x(), op.y(), op.z()});
                }
                if (!target.getMaterial().isSolid()) {
                    // docs/prompts/step4i-prompt.md: this entry just turned the cell non-solid, so its
                    // six neighbours may have lost the support they were relying on.
                    neighbourPositions.addNeighbours(op.x(), op.y(), op.z(), worldMinHeight, worldMaxHeight);
                }
            }
            if (op.sign() != null) {
                applySign(block, op.sign());
                if (!blockChanged) {
                    // Count a sign write as a change even if the block data was already equal.
                    addChanged(1);
                }
            }
            index++;
            advance(1);
            if (index < ops.size() && System.nanoTime() >= deadlineNanos) {
                return false;
            }
        }
        // Main loop is done; spend any remaining tick budget on the connection
        // pass (Fix 2) and then the support check (step4h-prompt.md), each
        // resumable across ticks exactly like the loop above.
        if (!connectionPass.step(deadlineNanos)) {
            return false;
        }
        if (!chestPairPass.step(deadlineNanos)) {
            return false;
        }
        return supportCheck.step(deadlineNanos);
    }

    /** Writes {@code sign}'s text/appearance onto {@code block}'s sign block state (Fix 3). */
    private static void applySign(Block block, SignData sign) {
        BlockState state = block.getState();
        if (!(state instanceof Sign signState)) {
            // Defensive only: RequestValidator already rejected "sign" for any
            // non-sign material up front, so this should be unreachable.
            return;
        }
        if (sign.front() != null) {
            applySide(signState.getSide(Side.FRONT), sign.front(), sign);
        }
        if (sign.back() != null) {
            applySide(signState.getSide(Side.BACK), sign.back(), sign);
        }
        signState.setWaxed(sign.waxed());
        signState.update(true, false);
    }

    private static void applySide(SignSide side, List<String> lines, SignData sign) {
        for (int i = 0; i < lines.size(); i++) {
            side.line(i, Component.text(lines.get(i)));
        }
        side.setGlowingText(sign.glowing());
        if (sign.color() != null) {
            side.setColor(DyeColor.valueOf(sign.color().trim().toUpperCase(Locale.ROOT)));
        }
    }

    @Override
    public JsonElement buildResult(long queuedMs, long elapsedMs) {
        JsonObject result = new JsonObject();
        result.addProperty("world", world.getName());
        result.addProperty("requested", ops.size());
        result.addProperty("changed", changed());
        result.addProperty("queuedMs", queuedMs);
        result.addProperty("elapsedMs", elapsedMs);
        result.addProperty("chestsPaired", chestPairPass.pairsMade());
        SupportWarnings.addTo(result, supportCheck, neighbourPositions.truncated());
        return result;
    }
}
