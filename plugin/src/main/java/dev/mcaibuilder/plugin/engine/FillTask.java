// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.mcaibuilder.plugin.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes a {@code fill_batch} request: a sequence of {@link FillOp}s run
 * in order (plan.md &sect;2.3 point 5: "clear first, then fill" must work), each
 * respecting its {@link FillMode} and optional {@code filter}.
 *
 * <p>Cursor state ({@code opIndex}, {@code cursorY}, {@code cursorZ},
 * {@code cursorX}) lets {@link #step} resume mid-row on the next tick. The
 * tick deadline is checked every {@link #DEADLINE_CHECK_INTERVAL} blocks
 * within a row (plan.md &sect;2.6 follow-up fix), not just once per row, so an
 * extreme shape such as a 1x1x500000 fill cannot stall a single tick.
 */
public final class FillTask extends BuildTask {

    private static final BlockData AIR = Material.AIR.createBlockData();

    /** How many blocks are processed between deadline checks, within a single row of x. */
    private static final int DEADLINE_CHECK_INTERVAL = 256;

    private final List<FillOp> ops;
    private final World world;
    private final long[] opChanged;
    private final List<int[]> connectablePositions = new ArrayList<>();
    private final ConnectionPass connectionPass;

    private int opIndex = 0;
    private int cursorY;
    private int cursorZ;
    private int cursorX;
    private boolean cursorInitialized = false;

    public FillTask(Region region, List<FillOp> ops, World world, boolean connect) {
        super(region);
        this.ops = ops;
        this.world = world;
        this.opChanged = new long[ops.size()];
        this.connectionPass = new ConnectionPass(world, connectablePositions, connect);
    }

    @Override
    public World world() {
        return world;
    }

    @Override
    public long volume() {
        long total = 0;
        for (FillOp op : ops) {
            total += op.region().volume();
        }
        return total;
    }

    @Override
    public boolean step(long deadlineNanos) {
        while (opIndex < ops.size()) {
            FillOp op = ops.get(opIndex);
            Region r = op.region();
            if (!cursorInitialized) {
                cursorY = r.minY();
                cursorZ = r.minZ();
                cursorX = r.minX();
                cursorInitialized = true;
            }
            while (cursorY <= r.maxY()) {
                while (cursorZ <= r.maxZ()) {
                    if (!processRowSegment(op, r, cursorY, cursorZ, deadlineNanos)) {
                        return false; // deadline hit mid-row; cursorX preserved for resume
                    }
                    cursorX = r.minX();
                    cursorZ++;
                    if (System.nanoTime() >= deadlineNanos) {
                        return false;
                    }
                }
                cursorZ = r.minZ();
                cursorY++;
            }
            opIndex++;
            cursorInitialized = false;
        }
        // Main cursor is done; spend any remaining tick budget on the connection
        // pass (Fix 2, docs/prompts/step4d-prompt.md), resumable across ticks
        // exactly like the main cursor above.
        return connectionPass.step(deadlineNanos);
    }

    /**
     * Processes blocks from {@code cursorX} to {@code r.maxX()} for one fixed
     * y/z row, checking the tick deadline every {@link #DEADLINE_CHECK_INTERVAL}
     * blocks. Leaves {@code cursorX} positioned to resume correctly on the
     * next call if it returns {@code false} (deadline hit with the row
     * unfinished); returns {@code true} once the row is fully processed.
     */
    private boolean processRowSegment(FillOp op, Region r, int y, int z, long deadlineNanos) {
        boolean edgeRow = (y == r.minY() || y == r.maxY() || z == r.minZ() || z == r.maxZ());
        int sinceCheck = 0;
        while (cursorX <= r.maxX()) {
            int x = cursorX;
            boolean isShell = edgeRow || x == r.minX() || x == r.maxX();
            BlockData target = switch (op.mode()) {
                case REPLACE, KEEP -> op.block();
                case OUTLINE -> isShell ? op.block() : null;
                case HOLLOW -> isShell ? op.block() : AIR;
                case WALLS -> WallGeometry.isWallCell(x, z, r) ? op.block() : null;
            };
            if (target != null) {
                Block block = world.getBlockAt(x, y, z);
                BlockData current = block.getBlockData();
                boolean write = !(op.mode() == FillMode.KEEP && !current.getMaterial().isAir())
                        && !(op.filter() != null && !current.matches(op.filter()));
                if (write && !current.equals(target)) {
                    // The only block-writing call allowed anywhere: never triggers physics.
                    block.setBlockData(target, false);
                    opChanged[opIndex]++;
                    addChanged(1);
                    if (ConnectionPass.isConnectable(target)) {
                        connectablePositions.add(new int[]{x, y, z});
                    }
                }
            }
            // else: OUTLINE/WALLS cell outside the shape for this mode, leave untouched.

            advance(1);
            cursorX++;
            sinceCheck++;
            if (sinceCheck >= DEADLINE_CHECK_INTERVAL) {
                sinceCheck = 0;
                if (cursorX <= r.maxX() && System.nanoTime() >= deadlineNanos) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public JsonElement buildResult(long queuedMs, long elapsedMs) {
        JsonObject result = new JsonObject();
        result.addProperty("world", world.getName());
        JsonArray opsJson = new JsonArray();
        long totalVolume = 0;
        for (int i = 0; i < ops.size(); i++) {
            long vol = ops.get(i).region().volume();
            totalVolume += vol;
            JsonObject opJson = new JsonObject();
            opJson.addProperty("index", i);
            opJson.addProperty("volume", vol);
            opJson.addProperty("changed", opChanged[i]);
            opsJson.add(opJson);
        }
        result.add("ops", opsJson);
        result.addProperty("totalVolume", totalVolume);
        result.addProperty("totalChanged", changed());
        result.addProperty("queuedMs", queuedMs);
        result.addProperty("elapsedMs", elapsedMs);
        return result;
    }
}
