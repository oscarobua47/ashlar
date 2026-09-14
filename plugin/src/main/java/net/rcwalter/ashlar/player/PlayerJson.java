// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.player;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Builds the JSON representation of an online player: position, facing,
 * game mode. Shared by the {@code players} RPC and the {@code chat} event
 * the {@code /ashlar} command pushes (step6a-prompt.md); previously lived
 * only in {@code PlayersHandler}, moved here so both can use it without one
 * depending on the other.
 */
public final class PlayerJson {

    private PlayerJson() {
    }

    public static JsonObject describe(Player player) {
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
