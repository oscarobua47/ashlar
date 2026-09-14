// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/**
 * A response envelope matching the wire protocol:
 * {@code {"id":..,"ok":true,"result":{...}}} or
 * {@code {"id":..,"ok":false,"error":{"code":..,"message":..}}}.
 */
public final class RpcResponse {

    private final JsonObject json;

    private RpcResponse(JsonObject json) {
        this.json = json;
    }

    public static RpcResponse ok(JsonElement id, JsonElement result) {
        JsonObject o = new JsonObject();
        o.add("id", id == null ? JsonNull.INSTANCE : id);
        o.addProperty("ok", true);
        o.add("result", result == null ? JsonNull.INSTANCE : result);
        return new RpcResponse(o);
    }

    public static RpcResponse error(JsonElement id, ErrorCode code, String message) {
        JsonObject o = new JsonObject();
        o.add("id", id == null ? JsonNull.INSTANCE : id);
        o.addProperty("ok", false);
        JsonObject error = new JsonObject();
        error.addProperty("code", code.name());
        error.addProperty("message", message);
        o.add("error", error);
        return new RpcResponse(o);
    }

    public JsonObject toJson() {
        return json;
    }
}
