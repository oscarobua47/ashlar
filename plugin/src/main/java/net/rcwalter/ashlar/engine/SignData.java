// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.engine;

import java.util.List;

/**
 * Optional sign text/appearance for one {@code set_blocks} entry (Fix 3,
 * docs/prompts/step4d-prompt.md). {@code front}/{@code back}, when non-null,
 * are always exactly 4 entries (missing lines padded to {@code ""} by
 * {@link RequestValidator}), each already checked to be at most 64
 * characters. At least one of {@code front}/{@code back} is non-null;
 * {@code color} is a {@link org.bukkit.DyeColor} name or {@code null} to
 * leave the side's color untouched.
 */
public record SignData(List<String> front, List<String> back, String color, boolean glowing, boolean waxed) {
}
