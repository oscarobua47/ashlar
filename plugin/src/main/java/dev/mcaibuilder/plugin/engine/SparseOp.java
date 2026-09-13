// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.mcaibuilder.plugin.engine;

import org.bukkit.block.data.BlockData;

/** One {@code set_blocks} entry: place {@code block} at a single coordinate. */
public record SparseOp(int x, int y, int z, BlockData block) {
}
