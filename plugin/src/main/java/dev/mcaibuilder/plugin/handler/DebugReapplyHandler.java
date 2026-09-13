// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.mcaibuilder.plugin.handler;

// TEMPORARY: step 4.7 (docs/prompts/step4d-prompt.md) Fix 2 experiment
// handler. Re-applies each listed position's current BlockData with
// physics=true, so the experiment can compare against the normal
// physics=false write path. Removed before the task finishes; not part of
// the shipped RPC surface.

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcaibuilder.plugin.net.ClientSession;
import dev.mcaibuilder.plugin.rpc.MainThread;
import dev.mcaibuilder.plugin.rpc.RpcHandler;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.concurrent.CompletableFuture;

public final class DebugReapplyHandler implements RpcHandler {

    @Override
    public CompletableFuture<JsonElement> handle(ClientSession session, JsonElement id, JsonObject params) {
        String worldName = params.get("world").getAsString();
        JsonArray positions = params.getAsJsonArray("positions");
        return MainThread.call(() -> {
            World world = Bukkit.getWorld(worldName);
            int applied = 0;
            for (JsonElement el : positions) {
                JsonArray pos = el.getAsJsonArray();
                int x = pos.get(0).getAsInt();
                int y = pos.get(1).getAsInt();
                int z = pos.get(2).getAsInt();
                Block block = world.getBlockAt(x, y, z);
                BlockData data = block.getBlockData();
                block.setBlockData(data, true);
                applied++;
            }
            JsonObject result = new JsonObject();
            result.addProperty("applied", applied);
            return (JsonElement) result;
        });
    }
}
