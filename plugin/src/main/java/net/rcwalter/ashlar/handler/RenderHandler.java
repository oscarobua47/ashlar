// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.rcwalter.ashlar.config.PluginConfig;
import net.rcwalter.ashlar.engine.HeightmapImageTask;
import net.rcwalter.ashlar.engine.HeightmapTypes;
import net.rcwalter.ashlar.engine.Region;
import net.rcwalter.ashlar.engine.RegionData;
import net.rcwalter.ashlar.engine.RenderTask;
import net.rcwalter.ashlar.engine.RequestValidator;
import net.rcwalter.ashlar.engine.TickBudgetExecutor;
import net.rcwalter.ashlar.engine.TopViewTask;
import net.rcwalter.ashlar.net.ClientSession;
import net.rcwalter.ashlar.render.HeightmapImageRenderer;
import net.rcwalter.ashlar.render.ImageRenderer;
import net.rcwalter.ashlar.rpc.ErrorCode;
import net.rcwalter.ashlar.rpc.MainThread;
import net.rcwalter.ashlar.rpc.RpcError;
import net.rcwalter.ashlar.rpc.RpcHandler;
import org.bukkit.HeightMap;
import org.bukkit.World;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * {@code render}: projects a region into a PNG (top/side/slice views,
 * docs/prompts/step4e-prompt.md). Reuses {@link RenderTask} (a budgeted,
 * main-thread {@code ReadTask} that also resolves map colors) for the data
 * read, then does the actual image work - {@link ImageRenderer#render} and
 * PNG encoding - on {@code renderExecutor}, off the main thread.
 *
 * <p>{@code executor.submit(task, ...)}'s future is completed on the main
 * thread ({@code TickBudgetExecutor#completeCurrent} runs inside {@code
 * tick()}); chaining with plain {@code thenCompose} would therefore run the
 * image work on the main thread too (a callback added before completion
 * runs on whichever thread calls {@code complete()}). {@code
 * thenComposeAsync(..., renderExecutor)} is used instead specifically to
 * force it off-thread regardless of timing.
 */
public final class RenderHandler implements RpcHandler {

    private static final int MAX_PNG_BYTES = 3 * 1024 * 1024;

    private final PluginConfig config;
    private final TickBudgetExecutor executor;
    private final ExecutorService renderExecutor;

    public RenderHandler(PluginConfig config, TickBudgetExecutor executor, ExecutorService renderExecutor) {
        this.config = config;
        this.executor = executor;
        this.renderExecutor = renderExecutor;
    }

    @Override
    public CompletableFuture<JsonElement> handle(ClientSession session, JsonElement id, JsonObject params) {
        try {
            RequestValidator validator = new RequestValidator(config);
            World world = validator.resolveWorld(params);
            String view = validator.peekRenderView(params);
            if ("heightmap".equals(view)) {
                return startHeightmapRender(validator, world, params, session, id);
            }
            if ("top".equals(view)) {
                return MainThread.call(() -> new int[]{world.getMinHeight(), world.getMaxHeight()})
                        .thenCompose(heights -> startTopRender(validator, world, params, heights, session, id));
            }
            return MainThread.call(() -> new int[]{world.getMinHeight(), world.getMaxHeight()})
                    .thenCompose(heights -> startRender(validator, world, params, heights, session, id));
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private CompletableFuture<JsonElement> startRender(RequestValidator validator, World world, JsonObject params,
            int[] heights, ClientSession session, JsonElement id) {
        try {
            Region region = validator.validateReadRegion(params, heights[0], heights[1], config.limits().maxReadVolume());
            RequestValidator.RenderParams renderParams = validator.validateRenderParams(params, region);
            RenderTask task = new RenderTask(region, world);
            String worldName = world.getName();
            return executor.submit(task, session, id)
                    .thenComposeAsync(ignored -> renderAsync(task, worldName, region, renderParams), renderExecutor);
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** Runs on {@link #renderExecutor}: {@link ImageRenderer#render}, PNG encoding, and the halve-on-oversize retry loop. */
    private CompletableFuture<JsonElement> renderAsync(RenderTask task, String worldName, Region region,
            RequestValidator.RenderParams rp) {
        try {
            RegionData data = task.regionData();
            int[] paletteArgb = task.paletteArgb();

            int scale = rp.scale();
            ImageRenderer.Output out;
            byte[] png;
            while (true) {
                out = ImageRenderer.render(data, paletteArgb, rp.view(), rp.sliceAxis(), rp.sliceAt(), scale, rp.grid());
                png = encodePng(out.pixels(), out.width(), out.height());
                if (png.length <= MAX_PNG_BYTES || out.scale() <= 1) {
                    break;
                }
                scale = out.scale() / 2;
            }
            if (png.length > MAX_PNG_BYTES) {
                throw new RpcError(ErrorCode.VOLUME_EXCEEDED,
                        "rendered PNG is " + png.length + " bytes, exceeding the " + MAX_PNG_BYTES
                                + "-byte limit even at scale=1; request a smaller area");
            }
            return CompletableFuture.completedFuture(buildResultJson(worldName, region, rp, out, png));
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new RpcError(ErrorCode.INTERNAL, "render failed: " + e.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // view: "top" (docs/prompts/step4g-prompt.md, Bug 2: area-priced, column-scan read)
    // ------------------------------------------------------------------

    /**
     * Unlike every other non-heightmap view, {@code "top"} is priced by x/z area, not volume (a top view reads at
     * most one block per column), so it validates via {@link RequestValidator#validateTopRegion} instead of
     * {@link RequestValidator#validateReadRegion} and reads with {@link TopViewTask} instead of {@link RenderTask}.
     */
    private CompletableFuture<JsonElement> startTopRender(RequestValidator validator, World world, JsonObject params,
            int[] worldHeights, ClientSession session, JsonElement id) {
        try {
            Region region = validator.validateTopRegion(params, worldHeights[0], worldHeights[1], config.limits().maxReadVolume());
            RequestValidator.RenderParams renderParams = validator.validateRenderParams(params, region);
            TopViewTask task = new TopViewTask(region, world);
            String worldName = world.getName();
            return executor.submit(task, session, id)
                    .thenComposeAsync(ignored -> renderTopAsync(task, worldName, region, renderParams), renderExecutor);
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** Runs on {@link #renderExecutor}: {@link ImageRenderer#renderTop}, PNG encoding, and the halve-on-oversize retry loop. */
    private CompletableFuture<JsonElement> renderTopAsync(TopViewTask task, String worldName, Region region,
            RequestValidator.RenderParams rp) {
        try {
            int scale = rp.scale();
            ImageRenderer.Output out;
            byte[] png;
            while (true) {
                out = ImageRenderer.renderTop(task.colorArgb(), task.blockNames(), task.topY(),
                        task.blocksWide(), task.blocksTall(), region.minX(), region.minZ(), scale, rp.grid());
                png = encodePng(out.pixels(), out.width(), out.height());
                if (png.length <= MAX_PNG_BYTES || out.scale() <= 1) {
                    break;
                }
                scale = out.scale() / 2;
            }
            if (png.length > MAX_PNG_BYTES) {
                throw new RpcError(ErrorCode.VOLUME_EXCEEDED,
                        "rendered PNG is " + png.length + " bytes, exceeding the " + MAX_PNG_BYTES
                                + "-byte limit even at scale=1; request a smaller area");
            }
            return CompletableFuture.completedFuture(buildResultJson(worldName, region, rp, out, png));
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new RpcError(ErrorCode.INTERNAL, "render failed: " + e.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // view: "heightmap" (docs/prompts/step4f-prompt.md)
    // ------------------------------------------------------------------

    /**
     * Unlike every other view, {@code "heightmap"} needs no world-min/max-height round trip (its area check is a
     * pure config comparison, same as the {@code heightmap} RPC itself - see {@code HeightmapHandler}) and reads a
     * 2D {@code from}/{@code to} instead of a 3D region, so it bypasses {@link #startRender}/{@link
     * RequestValidator#validateReadRegion} entirely.
     */
    private CompletableFuture<JsonElement> startHeightmapRender(RequestValidator validator, World world,
            JsonObject params, ClientSession session, JsonElement id) {
        try {
            RequestValidator.HeightmapRenderParams hp =
                    validator.validateHeightmapRenderParams(params, config.limits().maxReadVolume());
            HeightMap requestedMap = HeightmapTypes.resolve(hp.type());
            Region region = new Region(hp.x1(), 0, hp.z1(), hp.x2(), 0, hp.z2());
            HeightmapImageTask task =
                    new HeightmapImageTask(region, world, hp.x1(), hp.z1(), hp.x2(), hp.z2(), requestedMap);
            String worldName = world.getName();
            return executor.submit(task, session, id)
                    .thenComposeAsync(ignored -> renderHeightmapAsync(task, worldName, hp), renderExecutor);
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** Runs on {@link #renderExecutor}: {@link HeightmapImageRenderer#render}, PNG encoding, stats. */
    private CompletableFuture<JsonElement> renderHeightmapAsync(HeightmapImageTask task, String worldName,
            RequestValidator.HeightmapRenderParams hp) {
        try {
            int[][] heights = task.heights();
            int[][] classes = task.classes();
            int[][] liquidDepth = task.liquidDepth();

            int scale = hp.scale();
            HeightmapImageRenderer.Output out;
            byte[] png;
            while (true) {
                out = HeightmapImageRenderer.render(heights, classes, liquidDepth, hp.x1(), hp.z1(), scale, hp.grid(), hp.contour());
                png = encodePng(out.pixels(), out.width(), out.height());
                if (png.length <= MAX_PNG_BYTES || out.scale() <= 1) {
                    break;
                }
                scale = out.scale() / 2;
            }
            if (png.length > MAX_PNG_BYTES) {
                throw new RpcError(ErrorCode.VOLUME_EXCEEDED,
                        "rendered PNG is " + png.length + " bytes, exceeding the " + MAX_PNG_BYTES
                                + "-byte limit even at scale=1; request a smaller area");
            }

            int median = HeightmapImageRenderer.median(heights, classes);
            HeightmapImageRenderer.FlatZone zone =
                    HeightmapImageRenderer.largestFlatZone(heights, classes, hp.x1(), hp.z1(), median, 1);

            return CompletableFuture.completedFuture(buildHeightmapResultJson(
                    worldName, hp, out, png, task.min(), task.max(), median, zone,
                    task.liquidCells(), task.vegetationCells(), task.surfaceJson()));
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new RpcError(ErrorCode.INTERNAL, "render failed: " + e.getMessage()));
        }
    }

    private static JsonObject buildHeightmapResultJson(String worldName, RequestValidator.HeightmapRenderParams hp,
            HeightmapImageRenderer.Output out, byte[] png, int min, int max, int median,
            HeightmapImageRenderer.FlatZone zone, long liquidCells, long treeCells, JsonObject surface) {
        JsonObject json = new JsonObject();
        json.addProperty("world", worldName);
        json.addProperty("view", "heightmap");

        // 2D bounds (x,z only): the heightmap view has no y, unlike every other render view's 3D bounds.
        JsonObject bounds = new JsonObject();
        bounds.add("from", intArray2(hp.x1(), hp.z1()));
        bounds.add("to", intArray2(hp.x2(), hp.z2()));
        json.add("bounds", bounds);

        json.addProperty("width", out.width());
        json.addProperty("height", out.height());
        json.addProperty("scale", out.scale());

        JsonObject axes = new JsonObject();
        axes.addProperty("right", "+x (east)");
        axes.addProperty("down", "+z (south)");
        json.add("axes", axes);

        json.add("topLeft", intArray2(hp.x1(), hp.z1()));
        json.addProperty("grid", out.grid());
        json.addProperty("contour", out.contour());

        JsonArray legend = new JsonArray();
        for (HeightmapImageRenderer.Band band : out.legend()) {
            JsonObject b = new JsonObject();
            b.addProperty("color", band.colorHex());
            b.addProperty("label", band.label());
            legend.add(b);
        }
        json.add("legend", legend);

        JsonObject heightsJson = new JsonObject();
        heightsJson.addProperty("min", min);
        heightsJson.addProperty("max", max);
        heightsJson.addProperty("median", median);
        json.add("heights", heightsJson);

        json.add("surface", surface);

        if (zone != null) {
            JsonObject zoneJson = new JsonObject();
            zoneJson.addProperty("x1", zone.x1());
            zoneJson.addProperty("z1", zone.z1());
            zoneJson.addProperty("x2", zone.x2());
            zoneJson.addProperty("z2", zone.z2());
            zoneJson.addProperty("y", zone.y());
            zoneJson.addProperty("width", zone.width());
            zoneJson.addProperty("depth", zone.depth());
            json.add("flatZone", zoneJson);
        } else {
            json.add("flatZone", JsonNull.INSTANCE);
        }
        json.addProperty("liquidCells", liquidCells);
        json.addProperty("treeCells", treeCells);

        json.addProperty("png", Base64.getEncoder().encodeToString(png));
        json.addProperty("bytes", png.length);
        return json;
    }

    private static JsonArray intArray2(int a, int b) {
        JsonArray arr = new JsonArray();
        arr.add(a);
        arr.add(b);
        return arr;
    }

    private static byte[] encodePng(int[] pixels, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, pixels, 0, width);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bos);
        return bos.toByteArray();
    }

    private static JsonObject buildResultJson(String worldName, Region region, RequestValidator.RenderParams rp,
            ImageRenderer.Output out, byte[] png) {
        JsonObject json = new JsonObject();
        json.addProperty("world", worldName);
        json.addProperty("view", rp.view());

        JsonObject bounds = new JsonObject();
        bounds.add("from", intArray(region.minX(), region.minY(), region.minZ()));
        bounds.add("to", intArray(region.maxX(), region.maxY(), region.maxZ()));
        json.add("bounds", bounds);

        json.addProperty("width", out.width());
        json.addProperty("height", out.height());
        json.addProperty("scale", out.scale());

        JsonObject axes = new JsonObject();
        axes.addProperty("right", out.axisRight());
        axes.addProperty("down", out.axisDown());
        json.add("axes", axes);

        JsonArray topLeft = new JsonArray();
        topLeft.add(out.topLeftA());
        topLeft.add(out.topLeftB());
        json.add("topLeft", topLeft);

        json.addProperty("grid", rp.grid());

        JsonArray legend = new JsonArray();
        List<ImageRenderer.LegendEntry> entries = out.legend();
        for (ImageRenderer.LegendEntry entry : entries) {
            JsonObject e = new JsonObject();
            e.addProperty("block", entry.block());
            e.addProperty("color", entry.colorHex());
            e.addProperty("pixels", entry.pixels());
            legend.add(e);
        }
        json.add("legend", legend);

        json.addProperty("png", Base64.getEncoder().encodeToString(png));
        json.addProperty("bytes", png.length);
        return json;
    }

    private static JsonArray intArray(int a, int b, int c) {
        JsonArray arr = new JsonArray();
        arr.add(a);
        arr.add(b);
        arr.add(c);
        return arr;
    }
}
