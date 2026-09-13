// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.mcaibuilder.plugin.player;

/**
 * Pure functions that turn a Bukkit yaw into a cardinal facing direction and
 * a unit offset vector, used by {@code PlayersHandler} to report where a
 * player is looking ("in front of me") without any Bukkit dependency, so
 * this class can be unit tested directly.
 */
public final class Facing {

    // Index order matches the round(yaw/90) % 4 mapping below: 0=south,
    // 1=west, 2=north, 3=east (Bukkit yaw convention: 0=south/+Z,
    // 90=west/-X, 180=north/-Z, 270 or -90=east/+X).
    private static final String[] DIRECTIONS = {"south", "west", "north", "east"};

    private Facing() {
    }

    /**
     * Converts a Bukkit yaw (any float, including negative or {@literal >}
     * 360) into one of "south"/"west"/"north"/"east". Normalizes to
     * [0,360) first, then rounds to the nearest quarter turn.
     */
    public static String fromYaw(float yaw) {
        float normalized = yaw % 360f;
        if (normalized < 0f) {
            normalized += 360f;
        }
        int index = Math.round(normalized / 90f) % 4;
        return DIRECTIONS[index];
    }

    /** Unit offset {@code [dx, 0, dz]} for the given facing: one block in front of the player. */
    public static int[] offset(String facing) {
        return switch (facing) {
            case "south" -> new int[] {0, 0, 1};
            case "west" -> new int[] {-1, 0, 0};
            case "north" -> new int[] {0, 0, -1};
            case "east" -> new int[] {1, 0, 0};
            default -> throw new IllegalArgumentException("unknown facing: " + facing);
        };
    }
}
