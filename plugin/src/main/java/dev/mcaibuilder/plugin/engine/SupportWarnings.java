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

    /** Adds {@code "warnings": [...]} (always present, possibly empty) and {@code "warningsTruncated"} (only when true). */
    static void addTo(JsonObject result, SupportCheck check) {
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
    }
}
