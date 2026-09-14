// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.tool.text;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java port of how {@code mcp-server/src/tools/{mc-survey,mc-inspect,mc-render,mc-build}.ts}
 * assemble the pieces in {@link ReliefText}, {@link SliceText}, {@link HeightmapText} and {@link
 * WarningText} into each tool's final text output: header lines, blank lines, and trailing newlines
 * match the TypeScript originals exactly. 7.2b only needs to fetch data from the plugin's RPCs and
 * call these methods. No Bukkit dependency.
 */
public final class ToolText {

    private ToolText() {
    }

    // ------------------------------------------------------------------
    // mc_survey (mcp-server/src/tools/mc-survey.ts)
    // ------------------------------------------------------------------

    /** {@code format:"text"} path: the whole response is {@link ReliefText#renderRelief}. */
    public static String surveyTextFormat(ReliefText.ReliefInput input) {
        return ReliefText.renderRelief(input);
    }

    /**
     * Default {@code format:"image"} path's text content block (the image is a separate content
     * block, not built here): summary, blank, legend, blank, axes line, grid line, contour line.
     */
    public static String surveyImageText(HeightmapText.HeightmapRenderFields fields, String axisRight, String axisDown, int topLeftX, int topLeftZ, int grid) {
        List<String> lines = new ArrayList<>();
        lines.add(HeightmapText.summaryLine(fields));
        lines.add("");
        lines.add(HeightmapText.legendLine(fields.legend()));
        lines.add("");
        lines.add(axesLine(axisRight, axisDown, topLeftX, topLeftZ));
        lines.add(gridLine(grid));
        lines.add(HeightmapText.contourLine(fields.contour()));
        return String.join("\n", lines);
    }

    /** {@code matrix:true} on the image path appends this as an additional text content block. */
    public static String surveyMatrixText(int[][] heights, int x1, int z1) {
        return String.join("\n", ReliefText.renderMatrix(heights, x1, z1, 1));
    }

    // ------------------------------------------------------------------
    // mc_inspect (mcp-server/src/tools/mc-inspect.ts)
    // ------------------------------------------------------------------

    /** One sign block-entity's text/appearance, as read by the plugin's {@code read_region} RPC. */
    public record SignEntry(int[] pos, String block, List<String> front, List<String> back, boolean waxed) {
    }

    /** With no {@code slice}: the block-frequency table plus the optional Signs: section. */
    public static String inspectStatsText(BlockGrid decoded, String world, List<SignEntry> signs, boolean signsTruncated) {
        return appendSigns(SliceText.renderStats(decoded, world), signs, signsTruncated);
    }

    /** With {@code slice}: the ASCII cross-section plus the optional Signs: section. */
    public static String inspectSliceText(BlockGrid decoded, SliceText.SliceSpec spec, List<SignEntry> signs, boolean signsTruncated) {
        return appendSigns(SliceText.renderSlice(decoded, spec), signs, signsTruncated);
    }

    private static String appendSigns(String body, List<SignEntry> signs, boolean truncated) {
        String signsSection = renderSigns(signs, truncated);
        return signsSection != null ? body + "\n\n" + signsSection : body;
    }

    /**
     * Renders the {@code Signs:} section: one {@code x,y,z: "line1 | line2 | line3 | line4"} line
     * per sign, using the front side's text (or the back side, if the front is entirely blank).
     * Returns {@code null} when there are no signs, so output for a region with no signs is
     * unchanged.
     */
    public static String renderSigns(List<SignEntry> signs, boolean truncated) {
        if (signs == null || signs.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        lines.add("Signs:");
        for (SignEntry sign : signs) {
            int x = sign.pos()[0], y = sign.pos()[1], z = sign.pos()[2];
            boolean hasFrontText = sign.front().stream().anyMatch(l -> !l.isEmpty());
            String text = String.join(" | ", hasFrontText ? sign.front() : sign.back());
            lines.add("  " + x + "," + y + "," + z + ": \"" + text + "\"");
        }
        if (truncated) {
            lines.add("  (more signs exist in this region than could be listed; narrow the from/to range to see them)");
        }
        return String.join("\n", lines);
    }

    // ------------------------------------------------------------------
    // mc_render (mcp-server/src/tools/mc-render.ts)
    // ------------------------------------------------------------------

    private static String axesLine(String axisRight, String axisDown, int topLeftX, int topLeftZ) {
        return "Axes: right=" + axisRight + ", down=" + axisDown + "; the pixel at (0,0) is world coordinate ("
                + topLeftX + ", " + topLeftZ + ") along those two axes.";
    }

    private static String gridLine(int grid) {
        return grid > 0
                ? "Grid: a line every " + grid + " blocks (thicker every " + (grid * 5) + "), with coordinate labels along the top/left edges."
                : "Grid: disabled.";
    }

    /** The 3 header lines every {@code mc_render} view shares: View/Bounds/Size, Axes, Grid. */
    public static List<String> renderHeader(String view, int[] boundsFrom, int[] boundsTo, int width, int height, int scale,
                                             String axisRight, String axisDown, int topLeftX, int topLeftZ, int grid) {
        List<String> lines = new ArrayList<>();
        lines.add("View: " + view + " | Bounds: [" + joinCoords(boundsFrom) + "] -> [" + joinCoords(boundsTo) + "] | Size: "
                + width + "x" + height + "px @ scale " + scale + " (" + scale + "px/block)");
        lines.add(axesLine(axisRight, axisDown, topLeftX, topLeftZ));
        lines.add(gridLine(grid));
        return lines;
    }

    private static String joinCoords(int[] coords) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < coords.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(coords[i]);
        }
        return sb.toString();
    }

    /** The 3 tail lines for {@code view:"heightmap"}: contour line, summary line, legend line. */
    public static List<String> renderHeightmapTail(HeightmapText.HeightmapRenderFields fields) {
        List<String> lines = new ArrayList<>();
        lines.add(HeightmapText.contourLine(fields.contour()));
        lines.add(HeightmapText.summaryLine(fields));
        lines.add(HeightmapText.legendLine(fields.legend()));
        return lines;
    }

    /** One legend row for a non-heightmap view (top/facade/slice): Minecraft map color -> block. */
    public record ColorLegendEntry(String color, String block, int pixels) {
    }

    /** The tail lines for every other view: a fixed header plus one "color block (N px)" row per legend entry. */
    public static List<String> renderColorLegendTail(List<ColorLegendEntry> legend) {
        List<String> lines = new ArrayList<>();
        lines.add("Legend (Minecraft map color -> block; similar blocks can share a color, disambiguate here):");
        for (ColorLegendEntry entry : legend) {
            lines.add("  " + entry.color() + " " + entry.block() + " (" + entry.pixels() + " px)");
        }
        return lines;
    }

    /** Full composition of {@code mc_render}'s text content block (the image is a separate content block). */
    public static String renderText(String view, int[] boundsFrom, int[] boundsTo, int width, int height, int scale,
                                     String axisRight, String axisDown, int topLeftX, int topLeftZ, int grid,
                                     HeightmapText.HeightmapRenderFields heightmapFields, List<ColorLegendEntry> colorLegend) {
        List<String> lines = new ArrayList<>(renderHeader(view, boundsFrom, boundsTo, width, height, scale, axisRight, axisDown, topLeftX, topLeftZ, grid));
        if (heightmapFields != null) {
            lines.addAll(renderHeightmapTail(heightmapFields));
        } else {
            lines.addAll(renderColorLegendTail(colorLegend));
        }
        return String.join("\n", lines);
    }

    // ------------------------------------------------------------------
    // mc_build (mcp-server/src/tools/mc-build.ts)
    // ------------------------------------------------------------------

    public static String snapshotLine(String id, long volume) {
        return "Snapshot " + id + " created (volume " + volume + ") before building; call mc_restore({\"id\":\"" + id + "\"}) to undo this build.";
    }

    /** One entry of the "Fills:" section: the fill spec plus how many of its cells changed. */
    public record FillOpLine(int index, int[] from, int[] to, String block, long changed, long volume) {
    }

    /** The "Fills:" section: header, one line per op, then the total line. */
    public static List<String> fillsSection(List<FillOpLine> ops, long totalChanged, long totalVolume, long elapsedMs) {
        List<String> lines = new ArrayList<>();
        lines.add("Fills:");
        for (FillOpLine op : ops) {
            lines.add("  #" + op.index() + " [" + joinCoords(op.from()) + "] -> [" + joinCoords(op.to()) + "] " + op.block()
                    + ": " + op.changed() + "/" + op.volume() + " changed");
        }
        lines.add("  total: " + totalChanged + "/" + totalVolume + " changed in " + elapsedMs + "ms");
        return lines;
    }

    public static String blocksLine(long changed, long requested, long elapsedMs) {
        return "Blocks: " + changed + "/" + requested + " changed in " + elapsedMs + "ms";
    }

    /**
     * Full composition of {@code mc_build}'s result text: an optional snapshot line, an optional
     * Fills: section, an optional Blocks: line, then the WARNINGS section (if any).
     */
    public static String buildResultText(String snapshotLine, List<String> fillsSection, String blocksLine,
                                          List<WarningText.SupportWarning> warnings, boolean warningsTruncated) {
        List<String> lines = new ArrayList<>();
        if (snapshotLine != null) {
            lines.add(snapshotLine);
        }
        if (fillsSection != null) {
            lines.addAll(fillsSection);
        }
        if (blocksLine != null) {
            lines.add(blocksLine);
        }
        lines.addAll(WarningText.formatWarnings(warnings, warningsTruncated));
        return String.join("\n", lines);
    }

    /** The generic (non-PluginError) message mc-build.ts throws when a pre-build snapshot exceeds the volume limit. */
    public static String snapshotVolumeExceededMessage(String errMessage) {
        return errMessage + " The bounding box of this build's fills/blocks is too large to snapshot. Retry with "
                + "\"snapshot\": false, or split the build into smaller mc_build calls so each one's snapshot region stays under the limit.";
    }
}
