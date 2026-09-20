// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import com.google.gson.JsonElement;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.rpc.MainThread;
import org.bukkit.World;

import java.util.concurrent.CompletableFuture;

/**
 * The execution path behind {@code read_region} (spec &sect;3.4), split out
 * of {@code ReadRegionHandler} (plan.md step7). Callers must have already
 * validated {@code region} against {@code limits.max-read-volume}.
 */
public final class ReadRegionService {

    private final TickBudgetExecutor executor;

    public ReadRegionService(TickBudgetExecutor executor) {
        this.executor = executor;
    }

    /** Enqueues a full block-data read of {@code region}. Must not be called from the main thread. */
    public CompletableFuture<JsonElement> read(World world, Region region, InvocationContext ctx) {
        MainThread.assertNotPrimary("ReadRegionService.read");
        ReadTask task = new ReadTask(region, world);
        return executor.submit(task, ctx);
    }
}
