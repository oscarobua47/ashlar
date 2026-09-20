// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import com.google.gson.JsonElement;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.rpc.MainThread;
import org.bukkit.HeightMap;
import org.bukkit.World;

import java.util.concurrent.CompletableFuture;

/**
 * The execution path behind {@code heightmap} (spec &sect;3.3), split out
 * of {@code HeightmapHandler} (plan.md step7). Callers must have already
 * validated the requested area and resolved {@code heightMap}/{@code
 * typeName} (see {@code HeightmapTypes#resolve}).
 */
public final class HeightmapService {

    private final TickBudgetExecutor executor;

    public HeightmapService(TickBudgetExecutor executor) {
        this.executor = executor;
    }

    /** Enqueues a heightmap read over {@code [x1,z1]..[x2,z2]}. Must not be called from the main thread. */
    public CompletableFuture<JsonElement> heightmap(World world, int x1, int z1, int x2, int z2,
            HeightMap heightMap, String typeName, InvocationContext ctx) {
        MainThread.assertNotPrimary("HeightmapService.heightmap");
        Region region = new Region(x1, 0, z1, x2, 0, z2);
        HeightmapTask task = new HeightmapTask(region, world, x1, z1, x2, z2, heightMap, typeName);
        return executor.submit(task, ctx);
    }
}
