// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cc.wujm.ashlar.net.ClientSession;

import java.util.concurrent.CompletableFuture;

/**
 * A minimal, deliberately narrow exception to {@link RpcHandler}'s
 * transport-independent signature (plan.md step7): the two methods that
 * mutate or otherwise need the transport-level {@link ClientSession} itself
 * rather than the caller identity {@link InvocationContext} carries.
 * {@code subscribe} mutates the session's subscriptions; {@code
 * send_message} is grouped with it as the other purely transport/messaging
 * primitive, neither of which touches the world. Nothing else should
 * implement this - every world-related handler must use {@link RpcHandler}.
 */
public interface SessionRpcHandler {

    CompletableFuture<JsonElement> handle(ClientSession session, JsonElement id, JsonObject params);
}
