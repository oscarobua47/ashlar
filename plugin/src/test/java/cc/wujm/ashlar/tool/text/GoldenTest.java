// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.text;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Cross-language golden tests (docs/private/prompts/step7b-prompt.md): each {@code
 * src/test/resources/goldens/&lt;name&gt;.json} file holds {@code {input, expected}} where {@code
 * expected} was produced by actually running the TypeScript text-formatting functions ({@code
 * mcp-server/tools/goldens.mjs}) on {@code input}. This proves the Java port in this package is
 * byte-identical to the TypeScript original, not just structurally similar.
 */
class GoldenTest {

    /** Every golden file base name written by {@code mcp-server/tools/goldens.mjs}. */
    private static final List<String> NAMES = List.of(
            "relief-hill-lake-forest",
            "relief-wide-downsampled",
            "relief-flat-matrix",
            "relief-build-legend-bare",
            "relief-build-legend-liquid-vegetation",
            "inspect-stats-hollow-box",
            "inspect-slice-y-hollow-box",
            "inspect-slice-x-hollow-box",
            "inspect-slice-z-hollow-box",
            "inspect-slice-y-wide-downsampled",
            "heightmap-summary-with-zone",
            "heightmap-legend-line",
            "heightmap-contour-enabled",
            "heightmap-summary-no-zone-with-trees",
            "heightmap-contour-disabled",
            "warnings-set-a-mixed",
            "warnings-set-b-l-shape",
            "warnings-set-c-truncated",
            "error-volume_exceeded",
            "error-invalid_block",
            "error-world_not_allowed",
            "error-out_of_build_region",
            "error-queue_full",
            "error-disabled",
            "error-unauthorized",
            "error-unavailable",
            "error-timeout",
            "error-bad_request-yrange",
            "error-bad_request-generic",
            "error-some_future_code"
    );

    @TestFactory
    Stream<DynamicTest> goldens() {
        return NAMES.stream().map(name -> DynamicTest.dynamicTest(name, () -> runGolden(name)));
    }

    private void runGolden(String name) {
        JsonObject root = load(name);
        JsonElement input = root.get("input");
        JsonElement expected = root.get("expected");

        if (name.startsWith("relief-build-legend")) {
            assertLegend(input.getAsJsonObject(), expected.getAsJsonArray());
        } else if (name.startsWith("relief-")) {
            assertEquals(expected.getAsString(), ReliefText.renderRelief(toReliefInput(input.getAsJsonObject())));
        } else if (name.startsWith("inspect-stats-")) {
            JsonObject in = input.getAsJsonObject();
            BlockGrid grid = toBlockGrid(in.getAsJsonObject("region"));
            assertEquals(expected.getAsString(), SliceText.renderStats(grid, in.get("world").getAsString()));
        } else if (name.startsWith("inspect-slice-")) {
            JsonObject in = input.getAsJsonObject();
            BlockGrid grid = toBlockGrid(in.getAsJsonObject("region"));
            JsonObject spec = in.getAsJsonObject("spec");
            SliceText.SliceSpec sliceSpec = new SliceText.SliceSpec(spec.get("axis").getAsString(), spec.get("at").getAsInt());
            assertEquals(expected.getAsString(), SliceText.renderSlice(grid, sliceSpec));
        } else if (name.startsWith("heightmap-summary-")) {
            assertEquals(expected.getAsString(), HeightmapText.summaryLine(toHeightmapFields(input.getAsJsonObject())));
        } else if (name.equals("heightmap-legend-line")) {
            assertEquals(expected.getAsString(), HeightmapText.legendLine(toLegendBands(input.getAsJsonArray())));
        } else if (name.startsWith("heightmap-contour-")) {
            assertEquals(expected.getAsString(), HeightmapText.contourLine(input.getAsInt()));
        } else if (name.startsWith("warnings-")) {
            JsonObject in = input.getAsJsonObject();
            List<WarningText.SupportWarning> warnings = toWarnings(in.getAsJsonArray("warnings"));
            boolean truncated = in.get("truncated").getAsBoolean();
            List<String> actual = WarningText.formatWarnings(warnings, truncated);
            List<String> expectedLines = toStringList(expected.getAsJsonArray());
            assertEquals(expectedLines, actual);
        } else if (name.startsWith("error-")) {
            JsonObject in = input.getAsJsonObject();
            String code = in.get("code").getAsString();
            String message = in.get("message").getAsString();
            String where = in.get("pluginUrl").getAsString();
            assertEquals(expected.getAsString(), ErrorText.formatPluginError(code, message, where));
        } else {
            fail("no golden dispatch rule for " + name);
        }
    }

    private void assertLegend(JsonObject in, JsonArray expected) {
        int min = in.get("min").getAsInt();
        int max = in.get("max").getAsInt();
        boolean hasLiquid = in.get("hasLiquid").getAsBoolean();
        boolean hasVegetation = in.get("hasVegetation").getAsBoolean();
        List<ReliefText.LegendEntry> actual = ReliefText.buildLegend(min, max, hasLiquid, hasVegetation);
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            JsonObject e = expected.get(i).getAsJsonObject();
            assertEquals(e.get("char").getAsString(), actual.get(i).symbol(), "entry " + i + " char");
            assertEquals(e.get("label").getAsString(), actual.get(i).label(), "entry " + i + " label");
        }
    }

    // ------------------------------------------------------------------
    // JSON -> Java conversion helpers
    // ------------------------------------------------------------------

    private static ReliefText.ReliefInput toReliefInput(JsonObject in) {
        int[] from = toIntArray(in.getAsJsonArray("from"));
        int[] to = toIntArray(in.getAsJsonArray("to"));
        int[][] heights = to2DIntArray(in.getAsJsonArray("heights"));
        int[][] classes = to2DIntArray(in.getAsJsonArray("classes"));
        Map<String, Long> surface = toLongMap(in.getAsJsonObject("surface"));
        Boolean matrix = in.has("matrix") ? in.get("matrix").getAsBoolean() : null;
        return new ReliefText.ReliefInput(from, to, heights, classes, surface, matrix);
    }

    private static BlockGrid toBlockGrid(JsonObject region) {
        JsonObject bounds = region.getAsJsonObject("bounds");
        int[] from = toIntArray(bounds.getAsJsonArray("from"));
        int[] to = toIntArray(bounds.getAsJsonArray("to"));
        List<String> palette = new ArrayList<>();
        for (JsonElement el : region.getAsJsonArray("palette")) palette.add(el.getAsString());
        JsonArray runsJson = region.getAsJsonArray("runs");
        int[][] runs = new int[runsJson.size()][2];
        for (int i = 0; i < runsJson.size(); i++) {
            JsonArray run = runsJson.get(i).getAsJsonArray();
            runs[i][0] = run.get(0).getAsInt();
            runs[i][1] = run.get(1).getAsInt();
        }
        long volume = region.get("volume").getAsLong();
        return BlockGrid.decodeRegionDataJson(from, to, palette, runs, volume);
    }

    private static HeightmapText.HeightmapRenderFields toHeightmapFields(JsonObject in) {
        JsonObject bounds = in.getAsJsonObject("bounds");
        int[] boundsFrom = toIntArray(bounds.getAsJsonArray("from"));
        int[] boundsTo = toIntArray(bounds.getAsJsonArray("to"));
        JsonObject h = in.getAsJsonObject("heights");
        HeightmapText.Heights heights = new HeightmapText.Heights(h.get("min").getAsInt(), h.get("max").getAsInt(), h.get("median").getAsInt());
        Map<String, Long> surface = toLongMap(in.getAsJsonObject("surface"));
        HeightmapText.FlatZone flatZone = null;
        JsonElement fz = in.get("flatZone");
        if (fz != null && !fz.isJsonNull()) {
            JsonObject z = fz.getAsJsonObject();
            flatZone = new HeightmapText.FlatZone(z.get("x1").getAsInt(), z.get("z1").getAsInt(), z.get("x2").getAsInt(),
                    z.get("z2").getAsInt(), z.get("y").getAsInt(), z.get("width").getAsInt(), z.get("depth").getAsInt());
        }
        List<HeightmapText.LegendBand> legend = toLegendBands(in.getAsJsonArray("legend"));
        int liquidCells = in.get("liquidCells").getAsInt();
        int treeCells = in.get("treeCells").getAsInt();
        int contour = in.get("contour").getAsInt();
        return new HeightmapText.HeightmapRenderFields(boundsFrom, boundsTo, heights, surface, flatZone, legend, liquidCells, treeCells, contour);
    }

    private static List<HeightmapText.LegendBand> toLegendBands(JsonArray arr) {
        List<HeightmapText.LegendBand> list = new ArrayList<>();
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            list.add(new HeightmapText.LegendBand(o.get("color").getAsString(), o.get("label").getAsString()));
        }
        return list;
    }

    private static List<WarningText.SupportWarning> toWarnings(JsonArray arr) {
        List<WarningText.SupportWarning> list = new ArrayList<>();
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            JsonArray pos = o.getAsJsonArray("pos");
            list.add(new WarningText.SupportWarning(pos.get(0).getAsInt(), pos.get(1).getAsInt(), pos.get(2).getAsInt(),
                    o.get("block").getAsString(), o.get("reason").getAsString()));
        }
        return list;
    }

    private static int[] toIntArray(JsonArray arr) {
        int[] out = new int[arr.size()];
        for (int i = 0; i < arr.size(); i++) out[i] = arr.get(i).getAsInt();
        return out;
    }

    private static int[][] to2DIntArray(JsonArray arr) {
        int[][] out = new int[arr.size()][];
        for (int i = 0; i < arr.size(); i++) out[i] = toIntArray(arr.get(i).getAsJsonArray());
        return out;
    }

    private static Map<String, Long> toLongMap(JsonObject obj) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            map.put(e.getKey(), e.getValue().getAsLong());
        }
        return map;
    }

    private static List<String> toStringList(JsonArray arr) {
        List<String> out = new ArrayList<>();
        for (JsonElement el : arr) out.add(el.getAsString());
        return out;
    }

    private static JsonObject load(String name) {
        String resource = "goldens/" + name + ".json";
        try (InputStream is = GoldenTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (is == null) {
                throw new IllegalStateException("missing golden resource: " + resource);
            }
            return JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
