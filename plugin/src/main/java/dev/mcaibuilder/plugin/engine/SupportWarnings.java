// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.mcaibuilder.plugin.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Shared JSON encoding for {@link SupportCheck} results (docs/prompts/step4h-prompt.md), reused by
 * {@link FillTask}, {@link SparseTask} and {@link RestoreTask} so the {@code "warnings"}/{@code
 * "warningsTruncated"} shape is identical across {@code fill_batch}/{@code set_blocks}/{@code restore}.
 */
final class SupportWarnings {

    private SupportWarnings() {
    }

    /**
     * Adds {@code "warnings": [...]} (always present, possibly empty), {@code "warningsTruncated"}
     * (only when true), and {@code "neighbourChecksTruncated"} (only when {@code
     * neighbourPositionsTruncated} is true - docs/prompts/step4i-prompt.md: the {@link
     * NeighbourPositions} candidate list hit its own cap before every neighbour of a cleared area
     * could be queued for checking, independent of whether any warnings were actually found).
     */
    static void addTo(JsonObject result, SupportCheck check, boolean neighbourPositionsTruncated) {
        JsonArray warnings = new JsonArray();
        for (SupportCheck.Warning w : check.warnings()) {
            JsonObject wo = new JsonObject();
            JsonArray pos = new JsonArray();
            pos.add(w.x());
            pos.add(w.y());
            pos.add(w.z());
            wo.add("pos", pos);
            wo.addProperty("block", w.block());
            wo.addProperty("reason", w.reason());
            warnings.add(wo);
        }
        result.add("warnings", warnings);
        if (check.truncated()) {
            result.addProperty("warningsTruncated", true);
        }
        if (neighbourPositionsTruncated) {
            result.addProperty("neighbourChecksTruncated", true);
        }
    }
}
