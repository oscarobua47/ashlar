// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.tool;

/**
 * Thrown by a tool's argument parser (mirrors a zod validation failure) or by a tool body's own
 * pre-flight checks (mirrors a plain {@code throw new Error(...)} inside a TypeScript tool's async
 * handler, e.g. mc-render.ts's shape cross-checks or the 200,000-cell/block client-side limits).
 * {@link ToolRunner} turns this into an {@code isError:true} {@link ToolResult} with {@link
 * #getMessage()} used verbatim, exactly like {@code runTool}/{@code runToolContent} do for a plain
 * {@code Error} in TypeScript (spec section 4.3 part 4 / plan section 4.4).
 */
public final class ToolArgError extends RuntimeException {

    public ToolArgError(String message) {
        super(message);
    }
}
