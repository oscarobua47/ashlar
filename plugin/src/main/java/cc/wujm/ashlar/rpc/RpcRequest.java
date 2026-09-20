// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A parsed RPC request: {@code {id, method, params}}. {@code params} is
 * always a (possibly empty) JSON object, never {@code null}.
 */
public record RpcRequest(JsonElement id, String method, JsonObject params) {
}
