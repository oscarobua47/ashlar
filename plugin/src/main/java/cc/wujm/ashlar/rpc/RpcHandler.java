// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.concurrent.CompletableFuture;

/**
 * A single RPC method implementation, independent of the transport that
 * invoked it (plan.md step7). Handlers run on the WebSocket thread today and
 * must go through {@link cc.wujm.ashlar.rpc.MainThread} for any call
 * into Bukkit/Paper API; {@link InvocationContext} carries the caller
 * identity, the operation id used to tag {@code progress} events, and
 * cooperative cancellation, without exposing the {@code ClientSession} or
 * raw request id.
 *
 * <p>Two handlers ({@code send_message}, {@code subscribe}) need the
 * transport-level {@code ClientSession} itself (subscribe mutates its
 * subscriptions) and are registered as {@link SessionRpcHandler} instead;
 * every world-related handler uses this interface.
 */
public interface RpcHandler {

    CompletableFuture<JsonElement> handle(InvocationContext ctx, JsonObject params);
}
