// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.mcaibuilder.plugin.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcaibuilder.plugin.net.ClientSession;

import java.util.concurrent.CompletableFuture;

/**
 * A single RPC method implementation. Handlers run on the WebSocket thread
 * and must go through {@link dev.mcaibuilder.plugin.rpc.MainThread} for any
 * call into Bukkit/Paper API. {@code id} is the request id, needed by
 * handlers (e.g. {@code fill_batch}/{@code set_blocks}) that enqueue a
 * long-running task and must tag its {@code progress} events with it.
 */
public interface RpcHandler {

    CompletableFuture<JsonElement> handle(ClientSession session, JsonElement id, JsonObject params);
}
