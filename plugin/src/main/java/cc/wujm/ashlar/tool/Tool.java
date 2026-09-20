// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool;

import com.google.gson.JsonObject;
import cc.wujm.ashlar.rpc.InvocationContext;

import java.util.concurrent.CompletableFuture;

/**
 * One {@code mc_*} tool implementation: its client-facing {@link ToolSpec} plus the code that
 * runs a call. Implementations parse {@code args} themselves (no JSON-schema validation library,
 * plan.md Step 7.2b) and call the 7.1 services/handler statics with the given {@link
 * InvocationContext} - never building a session of their own.
 */
public interface Tool {

    ToolSpec spec();

    CompletableFuture<ToolResult> call(InvocationContext ctx, JsonObject args);
}
