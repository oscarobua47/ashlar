// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * Shared JSON argument-parsing helpers for the {@code tool/mc/Mc*.java} classes (plan.md Step
 * 7.2b): mirrors what each TypeScript tool's zod schema checks (required fields, types, tuple
 * shapes, enum membership, numeric ranges), reporting a field-level {@link ToolArgError} instead
 * of importing a JSON-schema validation library. Wording is close to zod's, not byte-identical
 * (the prompt allows this: the model only needs to understand what to fix).
 */
public final class ArgParse {

    private ArgParse() {
    }

    public static boolean has(JsonObject o, String field) {
        return o.has(field) && !o.get(field).isJsonNull();
    }

    public static String requireString(JsonObject o, String field) {
        if (!has(o, field) || !o.get(field).isJsonPrimitive() || !o.get(field).getAsJsonPrimitive().isString()) {
            throw new ToolArgError(field + ": required, must be a string");
        }
        return o.get(field).getAsString();
    }

    public static String optString(JsonObject o, String field) {
        if (!has(o, field)) {
            return null;
        }
        if (!o.get(field).isJsonPrimitive() || !o.get(field).getAsJsonPrimitive().isString()) {
            throw new ToolArgError(field + ": must be a string");
        }
        return o.get(field).getAsString();
    }

    public static Boolean optBooleanNullable(JsonObject o, String field) {
        if (!has(o, field)) {
            return null;
        }
        if (!o.get(field).isJsonPrimitive() || !o.get(field).getAsJsonPrimitive().isBoolean()) {
            throw new ToolArgError(field + ": must be a boolean");
        }
        return o.get(field).getAsBoolean();
    }

    public static boolean optBoolean(JsonObject o, String field, boolean fallback) {
        Boolean v = optBooleanNullable(o, field);
        return v != null ? v : fallback;
    }

    public static int requireInt(JsonObject o, String field) {
        if (!has(o, field) || !o.get(field).isJsonPrimitive() || !o.get(field).getAsJsonPrimitive().isNumber()) {
            throw new ToolArgError(field + ": required, must be an integer");
        }
        return o.get(field).getAsInt();
    }

    public static Integer optInt(JsonObject o, String field) {
        if (!has(o, field)) {
            return null;
        }
        if (!o.get(field).isJsonPrimitive() || !o.get(field).getAsJsonPrimitive().isNumber()) {
            throw new ToolArgError(field + ": must be an integer");
        }
        return o.get(field).getAsInt();
    }

    /** Required [x,y,z] (or any fixed length {@code n}) integer tuple. */
    public static int[] requireCoords3(JsonObject o, String field) {
        return requireCoordsN(o, field, 3);
    }

    public static int[] requireCoords2(JsonObject o, String field) {
        return requireCoordsN(o, field, 2);
    }

    private static int[] requireCoordsN(JsonObject o, String field, int n) {
        if (!has(o, field) || !o.get(field).isJsonArray()) {
            throw new ToolArgError(field + ": required, must be an array of " + n + " integers");
        }
        JsonArray arr = o.getAsJsonArray(field);
        if (arr.size() != n) {
            throw new ToolArgError(field + ": must have exactly " + n + " integers, got " + arr.size());
        }
        return coordsFrom(arr, field);
    }

    /** [x,y,z] or [x,z], for mc_render's {@code from}/{@code to} union shape. */
    public static int[] requireCoords2Or3(JsonObject o, String field) {
        if (!has(o, field) || !o.get(field).isJsonArray()) {
            throw new ToolArgError(field + ": required, must be an array of 2 or 3 integers");
        }
        JsonArray arr = o.getAsJsonArray(field);
        if (arr.size() != 2 && arr.size() != 3) {
            throw new ToolArgError(field + ": must have 2 or 3 integers, got " + arr.size());
        }
        return coordsFrom(arr, field);
    }

    private static int[] coordsFrom(JsonArray arr, String field) {
        int[] result = new int[arr.size()];
        for (int i = 0; i < result.length; i++) {
            JsonElement e = arr.get(i);
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
                throw new ToolArgError(field + ": must contain only integers");
            }
            result[i] = e.getAsInt();
        }
        return result;
    }

    public static String requireEnum(JsonObject o, String field, List<String> allowed) {
        String v = requireString(o, field);
        if (!allowed.contains(v)) {
            throw new ToolArgError(field + ": must be one of " + allowed + ", got '" + v + "'");
        }
        return v;
    }

    public static String optEnum(JsonObject o, String field, List<String> allowed, String fallback) {
        String v = optString(o, field);
        if (v == null) {
            return fallback;
        }
        if (!allowed.contains(v)) {
            throw new ToolArgError(field + ": must be one of " + allowed + ", got '" + v + "'");
        }
        return v;
    }

    public static JsonArray requireArray(JsonObject o, String field) {
        if (!has(o, field) || !o.get(field).isJsonArray()) {
            throw new ToolArgError(field + ": must be an array");
        }
        return o.getAsJsonArray(field);
    }

    public static JsonObject requireObject(JsonElement el, String what) {
        if (el == null || !el.isJsonObject()) {
            throw new ToolArgError(what + ": must be an object");
        }
        return el.getAsJsonObject();
    }
}
