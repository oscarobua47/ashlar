// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;

/** Small JSON building/reading helpers shared by the {@code Mc*} tool classes. */
final class JsonUtil {

    private JsonUtil() {
    }

    static JsonArray intArray(int... values) {
        JsonArray arr = new JsonArray();
        for (int v : values) {
            arr.add(v);
        }
        return arr;
    }

    static int[] toIntArrayAny(JsonArray arr) {
        int[] out = new int[arr.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = arr.get(i).getAsInt();
        }
        return out;
    }

    static List<String> toStringList(JsonArray arr) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : arr) {
            out.add(e.getAsString());
        }
        return out;
    }

    static JsonArray stringArray(List<String> values) {
        JsonArray arr = new JsonArray();
        for (String v : values) {
            arr.add(v);
        }
        return arr;
    }

    /** Mirrors JS's {@code String.prototype.padEnd}: pads with spaces on the right; never truncates. */
    static String padEnd(String s, int len) {
        if (s.length() >= len) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < len) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /** Comma-joins a JSON array of numbers, e.g. {@code [1,2,3]} -&gt; {@code "1,2,3"}. */
    static String joinArray(JsonArray arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(arr.get(i).getAsLong());
        }
        return sb.toString();
    }
}
