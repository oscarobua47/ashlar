// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.mcaibuilder.plugin.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcaibuilder.plugin.config.PluginConfig;
import dev.mcaibuilder.plugin.engine.Region;
import dev.mcaibuilder.plugin.engine.RegionData;
import dev.mcaibuilder.plugin.engine.RenderTask;
import dev.mcaibuilder.plugin.engine.RequestValidator;
import dev.mcaibuilder.plugin.engine.TickBudgetExecutor;
import dev.mcaibuilder.plugin.net.ClientSession;
import dev.mcaibuilder.plugin.render.ImageRenderer;
import dev.mcaibuilder.plugin.rpc.ErrorCode;
import dev.mcaibuilder.plugin.rpc.MainThread;
import dev.mcaibuilder.plugin.rpc.RpcError;
import dev.mcaibuilder.plugin.rpc.RpcHandler;
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
                png = encodePng(out);
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

    private static byte[] encodePng(ImageRenderer.Output out) throws IOException {
        BufferedImage image = new BufferedImage(out.width(), out.height(), BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, out.width(), out.height(), out.pixels(), 0, out.width());
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
