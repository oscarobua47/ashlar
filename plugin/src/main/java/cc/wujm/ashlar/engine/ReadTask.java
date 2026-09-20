// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
 * Executes a {@code read_region} request: scans {@link #region()} in {@code
 * y,z,x} order (plan.md &sect;3.3) and feeds each cell's {@link
 * BlockData#getAsString()} into a {@link RegionData.Encoder}. Also reused by
 * {@code snapshot} (plan.md &sect;3.1): the handler submits a {@code ReadTask}
 * and, once its future completes, reads {@link #regionData()} to build the
 * stored {@link cc.wujm.ashlar.snapshot.Snapshot} instead of using
 * the raw {@code read_region}-shaped JSON this class also produces.
 *
 * <p>{@code changed()} is never incremented; this task is read-only. The
 * tick deadline is checked every {@link #DEADLINE_CHECK_INTERVAL} blocks
 * within a row (same strategy as {@link FillTask}, plan.md &sect;2.6), so a
 * large read cannot stall a tick either.
 *
 * <p>Fix 3 (docs/prompts/step4d-prompt.md): every scanned block whose
 * material name ends with {@code _sign} or {@code _hanging_sign} also has
 * its sign block-entity text collected into {@code signs} (capped at
 * {@link #MAX_SIGNS}; {@link #signsTruncated} is set beyond the cap), added
 * to the {@code read_region} result but not consumed by the {@code
 * snapshot} path above - snapshots still ignore block-entity data.
 *
 * <p>Not {@code final}: {@link RenderTask} (docs/prompts/step4e-prompt.md)
 * extends it to reuse the budgeted scan, overriding only {@link
 * #buildResult} to additionally resolve map colors.
 */
public class ReadTask extends BuildTask {

    private static final int DEADLINE_CHECK_INTERVAL = 256;
    private static final int MAX_SIGNS = 200;

    private final World world;
    private final RegionData.Encoder encoder = new RegionData.Encoder();
    private final List<JsonObject> signs = new ArrayList<>();
    private boolean signsTruncated = false;

    private int cursorY;
    private int cursorZ;
    private int cursorX;
    private boolean cursorInitialized = false;
    private RegionData result;

    public ReadTask(Region region, World world) {
        super(region);
        this.world = world;
    }

    @Override
    public World world() {
        return world;
    }

    @Override
    public long volume() {
        return region().volume();
    }

    @Override
    public boolean step(long deadlineNanos) {
        Region r = region();
        if (!cursorInitialized) {
            cursorY = r.minY();
            cursorZ = r.minZ();
            cursorX = r.minX();
            cursorInitialized = true;
        }
        while (cursorY <= r.maxY()) {
            while (cursorZ <= r.maxZ()) {
                if (!readRowSegment(r, deadlineNanos)) {
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
        return true;
    }

    /** Reads blocks from {@code cursorX} to {@code r.maxX()} for the current y/z row. Returns false if the deadline was hit mid-row. */
    private boolean readRowSegment(Region r, long deadlineNanos) {
        int sinceCheck = 0;
        while (cursorX <= r.maxX()) {
            Block block = world.getBlockAt(cursorX, cursorY, cursorZ);
            BlockData data = block.getBlockData();
            encoder.add(data.getAsString());
            String materialName = data.getMaterial().name().toLowerCase(Locale.ROOT);
            if (materialName.endsWith("_sign") || materialName.endsWith("_hanging_sign")) {
                collectSign(block, cursorX, cursorY, cursorZ);
            }
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

    /** Adds one {@code signs} entry for a sign block, up to {@link #MAX_SIGNS}. */
    private void collectSign(Block block, int x, int y, int z) {
        if (signs.size() >= MAX_SIGNS) {
            signsTruncated = true;
            return;
        }
        BlockState state = block.getState();
        if (!(state instanceof Sign sign)) {
            return;
        }
        JsonObject entry = new JsonObject();
        JsonArray pos = new JsonArray();
        pos.add(x);
        pos.add(y);
        pos.add(z);
        entry.add("pos", pos);
        entry.addProperty("block", block.getBlockData().getAsString());
        entry.add("front", linesJson(sign.getSide(Side.FRONT)));
        entry.add("back", linesJson(sign.getSide(Side.BACK)));
        entry.addProperty("waxed", sign.isWaxed());
        signs.add(entry);
    }

    private static JsonArray linesJson(SignSide side) {
        JsonArray arr = new JsonArray();
        for (int i = 0; i < 4; i++) {
            arr.add(PlainTextComponentSerializer.plainText().serialize(side.line(i)));
        }
        return arr;
    }

    @Override
    public JsonElement buildResult(long queuedMs, long elapsedMs) {
        result = encoder.finish(region());
        JsonObject json = result.toJson();
        JsonArray signsArray = new JsonArray();
        for (JsonObject sign : signs) {
            signsArray.add(sign);
        }
        json.add("signs", signsArray);
        json.addProperty("signsTruncated", signsTruncated);
        return json;
    }

    /** The decoded region data. Only populated once {@link #buildResult} has run. */
    public RegionData regionData() {
        return result;
    }
}
