// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.mcaibuilder.plugin.engine;

import org.bukkit.block.data.BlockData;

/**
 * One {@code fill_batch} operation: fill {@code region} with {@code block}
 * according to {@code mode}. {@code filter}, when non-null, restricts writes
 * to cells whose current block matches it via {@link BlockData#matches},
 * which treats unspecified block-state properties as wildcards.
 */
public record FillOp(Region region, BlockData block, FillMode mode, BlockData filter) {
}
