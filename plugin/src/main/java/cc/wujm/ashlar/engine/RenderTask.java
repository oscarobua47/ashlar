// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import org.bukkit.World;

/**
 * Executes the read side of a {@code render} request (docs/prompts/step4e-prompt.md
 * &sect;Design): reuses {@link ReadTask}'s budgeted main-thread scan to
 * obtain a {@link RegionData}, then - still on the main thread, inside
 * {@link #buildResult} (called by {@link TickBudgetExecutor#completeCurrent}) -
 * resolves every palette entry's vanilla map color via {@link
 * MapColorResolver}.
 *
 * <p>{@link #buildResult} intentionally returns a throwaway {@link
 * JsonNull}: this task's {@link java.util.concurrent.CompletableFuture} (as
 * returned by {@code TickBudgetExecutor#submit}) is never sent to the
 * client directly. {@code RenderHandler} chains {@code
 * thenComposeAsync(..., renderExecutor)} onto it and reads {@link
 * #regionData()}/{@link #paletteArgb()} from this task object once that
 * future completes, building the actual PNG response off the main thread.
 */
public final class RenderTask extends ReadTask {

    private int[] paletteArgb;

    public RenderTask(Region region, World world) {
        super(region, world);
    }

    @Override
    public JsonElement buildResult(long queuedMs, long elapsedMs) {
        super.buildResult(queuedMs, elapsedMs); // populates regionData()
        RegionData data = regionData();
        int[] argb = new int[data.palette().size()];
        for (int i = 0; i < argb.length; i++) {
            argb[i] = MapColorResolver.resolve(data.palette().get(i));
        }
        this.paletteArgb = argb;
        return JsonNull.INSTANCE;
    }

    /** ARGB color for each {@link RegionData#palette()} entry, parallel to it. Only populated once {@link #buildResult} has run. */
    public int[] paletteArgb() {
        return paletteArgb;
    }
}
