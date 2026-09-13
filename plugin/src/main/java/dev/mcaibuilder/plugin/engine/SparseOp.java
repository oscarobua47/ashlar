// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.mcaibuilder.plugin.engine;

import org.bukkit.block.data.BlockData;

/**
 * One {@code set_blocks} entry: place {@code block} at a single coordinate,
 * optionally writing sign text/appearance via {@code sign} (Fix 3,
 * docs/prompts/step4d-prompt.md; {@code null} when the entry has no {@code
 * "sign"} object).
 */
public record SparseOp(int x, int y, int z, BlockData block, SignData sign) {
}
