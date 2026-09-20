// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Holds the nine {@code mc_*} tools in registration order (plan.md Step 7.2b: same order as
 * {@code mcp-server/src/tools/index.ts}'s {@code registerAllTools}): mc_status, mc_players,
 * mc_survey, mc_build, mc_inspect, mc_render, mc_snapshot, mc_restore, mc_command.
 *
 * <p>{@link #catalog(Predicate)} is a hook for a later per-token visibility filter (plan.md v0.5
 * scopes); no scopes exist yet, so {@link #catalog()} passes a filter that accepts everything.
 */
public final class ToolRegistry {

    private final Map<String, Tool> byName = new LinkedHashMap<>();

    public ToolRegistry(List<Tool> tools) {
        for (Tool tool : tools) {
            String name = tool.spec().name();
            if (byName.putIfAbsent(name, tool) != null) {
                throw new IllegalArgumentException("duplicate tool name: " + name);
            }
        }
    }

    /** Every registered tool's spec, in registration order. */
    public List<ToolSpec> catalog() {
        return catalog(spec -> true);
    }

    /** Registered tool specs, in registration order, that pass {@code visible}. */
    public List<ToolSpec> catalog(Predicate<ToolSpec> visible) {
        List<ToolSpec> specs = new ArrayList<>();
        for (Tool tool : byName.values()) {
            ToolSpec spec = tool.spec();
            if (visible.test(spec)) {
                specs.add(spec);
            }
        }
        return specs;
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** Names of every registered tool, in registration order - used to list valid names in an "unknown tool" error. */
    public List<String> names() {
        return List.copyOf(byName.keySet());
    }

    /**
     * The server-level instructions text from {@code /tools/instructions.txt}, or {@code null} when
     * the resource is missing. Read once per call; the file is tiny and this is only hit by
     * {@code tool_catalog}.
     */
    public String instructions() {
        try (java.io.InputStream in = ToolRegistry.class.getResourceAsStream("/tools/instructions.txt")) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        } catch (java.io.IOException e) {
            return null;
        }
    }
}
