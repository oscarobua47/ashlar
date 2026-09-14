// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.tool.text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java port of {@code mcp-server/src/tools/warnings.ts}: builds the WARNINGS section appended
 * to mc_build/mc_restore's text. No Bukkit dependency.
 */
public final class WarningText {

    private WarningText() {
    }

    /** One flagged block, as returned by the plugin's fill_batch/set_blocks/restore RPCs. */
    public record SupportWarning(int x, int y, int z, String block, String reason) {
    }

    private static final String HEADER =
            "WARNINGS (blocks that would fall or pop off in vanilla, including ones next to something you just "
                    + "removed; physics is disabled so they stay - fix them):";

    // The plugin reports "embedded" as a short, stable reason string (its Warning record has no room for a
    // per-block direction suggestion); expanded here into the fuller, model-actionable phrasing.
    private static final String EMBEDDED_TEXT =
            "embedded - standing torch surrounded by solid blocks on 3+ horizontal sides; "
                    + "did you mean wall_torch in the air block outside the wall, instead of replacing a wall block with a standing torch?";

    /**
     * Builds the WARNINGS section, or an empty list when there is nothing to report (the common
     * case). Consecutive positions sharing the same block state and reason are grouped into one line
     * covering a range along whichever single axis varies.
     */
    public static List<String> formatWarnings(List<SupportWarning> warnings, boolean truncated) {
        if (warnings.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        for (Group group : groupWarnings(warnings)) {
            lines.add("  " + formatGroup(group));
        }
        if (truncated) {
            lines.add("  (warnings capped at 50 for this call; more may remain - fix these, then re-run to see the rest)");
        }
        return lines;
    }

    private static final int AXIS_NONE = -1;
    private static final int AXIS_X = 0;
    private static final int AXIS_Y = 1;
    private static final int AXIS_Z = 2;

    private record Group(int count, String block, String reason, int axis, int lo, int hi, int px, int py, int pz) {
    }

    private static String axisName(int axis) {
        return axis == AXIS_X ? "x" : axis == AXIS_Y ? "y" : "z";
    }

    private static String formatGroup(Group g) {
        String reasonText = g.reason().equals("embedded") ? EMBEDDED_TEXT : g.reason();
        String prefix = g.count() + "x " + g.block() + " at ";
        if (g.axis() == AXIS_NONE) {
            return prefix + g.px() + "," + g.py() + "," + g.pz() + ": " + reasonText;
        }
        String loc = (g.axis() == AXIS_X ? "x=" + g.lo() + ".." + g.hi() : "x=" + g.px()) + " "
                + (g.axis() == AXIS_Y ? "y=" + g.lo() + ".." + g.hi() : "y=" + g.py()) + " "
                + (g.axis() == AXIS_Z ? "z=" + g.lo() + ".." + g.hi() : "z=" + g.pz());
        return prefix + loc + ": " + reasonText;
    }

    private static List<Group> groupWarnings(List<SupportWarning> warnings) {
        Map<String, List<SupportWarning>> byKey = new LinkedHashMap<>();
        for (SupportWarning w : warnings) {
            String key = w.block() + " " + w.reason();
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(w);
        }

        List<Group> groups = new ArrayList<>();
        for (List<SupportWarning> list : byKey.values()) {
            groups.addAll(groupRuns(list));
        }
        return groups;
    }

    /** All entries here already share the same block+reason; groups consecutive runs along one axis at a time. */
    private static List<Group> groupRuns(List<SupportWarning> list) {
        List<Integer> remaining = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) remaining.add(i);
        List<Group> groups = new ArrayList<>();

        for (int axis = AXIS_X; axis <= AXIS_Z; axis++) {
            if (remaining.isEmpty()) break;
            Map<String, List<Integer>> byOthers = new LinkedHashMap<>();
            for (int i : remaining) {
                SupportWarning p = list.get(i);
                String otherKey = axis == AXIS_X ? p.y() + "," + p.z() : axis == AXIS_Y ? p.x() + "," + p.z() : p.x() + "," + p.y();
                byOthers.computeIfAbsent(otherKey, k -> new ArrayList<>()).add(i);
            }

            java.util.Set<Integer> used = new java.util.HashSet<>();
            for (List<Integer> idxsList : byOthers.values()) {
                List<Integer> idxs = new ArrayList<>(idxsList);
                int finalAxis = axis;
                idxs.sort((a, b) -> Integer.compare(coord(list.get(a), finalAxis), coord(list.get(b), finalAxis)));
                int runStart = 0;
                for (int i = 1; i <= idxs.size(); i++) {
                    boolean brokeRun = i == idxs.size()
                            || coord(list.get(idxs.get(i)), axis) != coord(list.get(idxs.get(i - 1)), axis) + 1;
                    if (!brokeRun) continue;
                    List<Integer> run = idxs.subList(runStart, i);
                    runStart = i;
                    if (run.size() < 2) continue; // singles fall through to the next axis / final pass
                    SupportWarning first = list.get(run.get(0));
                    SupportWarning last = list.get(run.get(run.size() - 1));
                    groups.add(new Group(run.size(), first.block(), first.reason(), axis, coord(first, axis), coord(last, axis),
                            first.x(), first.y(), first.z()));
                    for (int ix : run) used.add(ix);
                }
            }
            List<Integer> next = new ArrayList<>();
            for (int i : remaining) {
                if (!used.contains(i)) next.add(i);
            }
            remaining = next;
        }

        for (int i : remaining) {
            SupportWarning w = list.get(i);
            groups.add(new Group(1, w.block(), w.reason(), AXIS_NONE, 0, 0, w.x(), w.y(), w.z()));
        }

        return groups;
    }

    private static int coord(SupportWarning w, int axis) {
        return axis == AXIS_X ? w.x() : axis == AXIS_Y ? w.y() : w.z();
    }
}
