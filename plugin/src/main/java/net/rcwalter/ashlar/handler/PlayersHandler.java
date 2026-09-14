// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.rcwalter.ashlar.net.ClientSession;
import net.rcwalter.ashlar.player.Facing;
import net.rcwalter.ashlar.rpc.MainThread;
import net.rcwalter.ashlar.rpc.RpcHandler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;

/**
 * {@code players}: read-only roster of online players, giving spatial
 * context for requests like "build here" or "put it in front of me". Params:
 * {@code {}}. All Bukkit access happens on the main thread via
 * {@link MainThread#call}; not recorded in {@link net.rcwalter.ashlar.log.OperationLog}
 * since it does not touch the world.
 */
public final class PlayersHandler implements RpcHandler {

    @Override
    public CompletableFuture<JsonElement> handle(ClientSession session, JsonElement id, JsonObject params) {
        return MainThread.call(() -> {
            JsonArray players = new JsonArray();
            for (Player player : Bukkit.getOnlinePlayers()) {
                players.add(describe(player));
            }
            JsonObject result = new JsonObject();
            result.addProperty("count", players.size());
            result.add("players", players);
            return (JsonElement) result;
        });
    }

    private JsonObject describe(Player player) {
        Location loc = player.getLocation();
        int blockX = loc.getBlockX();
        int blockY = loc.getBlockY();
        int blockZ = loc.getBlockZ();
        float yaw = loc.getYaw();
        String facing = Facing.fromYaw(yaw);
        int[] offset = Facing.offset(facing);

        JsonObject json = new JsonObject();
        json.addProperty("name", player.getName());
        json.addProperty("uuid", player.getUniqueId().toString());
        json.addProperty("world", loc.getWorld().getName());
        json.add("pos", intArray(blockX, blockY, blockZ));
        json.add("exact", doubleArray(round2(loc.getX()), round2(loc.getY()), round2(loc.getZ())));
        json.addProperty("yaw", yaw);
        json.addProperty("pitch", loc.getPitch());
        json.addProperty("facing", facing);
        json.add("inFront", intArray(blockX + offset[0], blockY + offset[1], blockZ + offset[2]));
        json.addProperty("gameMode", player.getGameMode().name());
        json.addProperty("flying", player.isFlying());
        json.addProperty("health", player.getHealth());
        return json;
    }

    private static JsonArray intArray(int a, int b, int c) {
        JsonArray arr = new JsonArray();
        arr.add(a);
        arr.add(b);
        arr.add(c);
        return arr;
    }

    private static JsonArray doubleArray(double a, double b, double c) {
        JsonArray arr = new JsonArray();
        arr.add(a);
        arr.add(b);
        arr.add(c);
        return arr;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
