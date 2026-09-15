// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.player;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.util.RayTraceResult;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Block;
import com.google.gson.JsonNull;
import com.google.gson.JsonElement;
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
        json.add("lookingAt", lookingAt(player));
        json.addProperty("gameMode", player.getGameMode().name());
        json.addProperty("flying", player.isFlying());
        json.addProperty("health", player.getHealth());
        return json;
    }

    /**
     * The first block the player's line of sight hits within 16 blocks, with the face it hits
     * ({@code null} when nothing is in range). This is what "this", "here" or "that wall" usually
     * mean, so the assistant gets it with every request instead of searching with inspect slices.
     */
    public static JsonElement lookingAt(Player player) {
        RayTraceResult hit = player.rayTraceBlocks(16.0);
        if (hit == null || hit.getHitBlock() == null) {
            return JsonNull.INSTANCE;
        }
        Block b = hit.getHitBlock();
        JsonObject json = new JsonObject();
        json.add("pos", intArray(b.getX(), b.getY(), b.getZ()));
        json.addProperty("block", b.getBlockData().getAsString());
        BlockFace face = hit.getHitBlockFace();
        json.addProperty("face", face == null ? "unknown" : face.name().toLowerCase(java.util.Locale.ROOT));
        return json;
    }

    /** One-line form of {@link #lookingAt} for the assistant's context line, or {@code null}. */
    public static String lookingAtText(Player player) {
        JsonElement e = lookingAt(player);
        if (e.isJsonNull()) {
            return null;
        }
        JsonObject o = e.getAsJsonObject();
        JsonArray pos = o.getAsJsonArray("pos");
        return o.get("block").getAsString() + " at " + pos.get(0).getAsInt() + "," + pos.get(1).getAsInt() + ","
                + pos.get(2).getAsInt() + " (" + o.get("face").getAsString() + " face)";
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
