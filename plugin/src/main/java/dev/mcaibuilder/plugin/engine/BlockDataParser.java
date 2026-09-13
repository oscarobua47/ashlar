// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.mcaibuilder.plugin.engine;

import dev.mcaibuilder.plugin.rpc.ErrorCode;
import dev.mcaibuilder.plugin.rpc.RpcError;
import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Parses block-data strings such as {@code "minecraft:oak_stairs[facing=north]"}
 * via {@link Bukkit#createBlockData(String)}. That call is a pure block-state
 * registry lookup and is safe to call off the main thread (unlike methods on
 * a {@code World} instance). Results are cached in a {@link ConcurrentHashMap}
 * keyed by the raw string; cached {@link BlockData} instances are treated as
 * read-only value objects everywhere in this codebase and are never mutated
 * after being handed out, so sharing them across requests/threads is safe.
 */
public final class BlockDataParser {

    private static final ConcurrentHashMap<String, BlockData> CACHE = new ConcurrentHashMap<>();

    private BlockDataParser() {
    }

    public static BlockData parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new RpcError(ErrorCode.INVALID_BLOCK, "block string is empty");
        }
        BlockData cached = CACHE.get(raw);
        if (cached != null) {
            return cached;
        }
        BlockData parsed;
        try {
            parsed = Bukkit.createBlockData(raw);
        } catch (IllegalArgumentException e) {
            throw new RpcError(ErrorCode.INVALID_BLOCK, "cannot parse block '" + raw + "'");
        }
        CACHE.put(raw, parsed);
        return parsed;
    }
}
