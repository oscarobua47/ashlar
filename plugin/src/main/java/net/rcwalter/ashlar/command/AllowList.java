// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Plugin-native allow list for {@code /ashlar} (step6e-prompt.md): server
 * operators without a permissions plugin can grant individual players
 * access to {@code /ashlar} ({@code ashlar.use} still works for op / a
 * permissions plugin; this is the third way in). Pure Java - no Bukkit - so
 * it is unit-testable with a temp file; {@link net.rcwalter.ashlar.AshlarPlugin}
 * points it at {@code plugins/Ashlar/allowed-players.yml}.
 *
 * <p>Persisted as a simple YAML block sequence under the {@code players}
 * key, names stored exactly as typed but matched case-insensitively (the
 * same policy the command layer uses to resolve offline players by name).
 */
public final class AllowList {

    private final Path savePath;
    private final Logger logger;
    private final List<String> names = new CopyOnWriteArrayList<>();

    public AllowList(Path savePath, Logger logger) {
        this.savePath = savePath;
        this.logger = logger;
    }

    /** Loads the list from disk, replacing whatever was in memory. A missing file means an empty list. */
    public void load() {
        names.clear();
        if (!Files.isRegularFile(savePath)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(savePath, StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                if (!trimmed.startsWith("- ")) {
                    continue;
                }
                String name = unquote(trimmed.substring(2).strip());
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        } catch (IOException e) {
            logger.warning("Failed to load allow list from " + savePath + ": " + e.getMessage());
        }
    }

    /** Adds {@code name} and persists; returns false (no write) if already present, case-insensitively. */
    public boolean add(String name) {
        if (contains(name)) {
            return false;
        }
        names.add(name);
        save();
        return true;
    }

    /** Removes {@code name} (case-insensitive) and persists; returns false if it was not present. */
    public boolean remove(String name) {
        for (String existing : names) {
            if (existing.equalsIgnoreCase(name)) {
                names.remove(existing);
                save();
                return true;
            }
        }
        return false;
    }

    public boolean contains(String name) {
        for (String existing : names) {
            if (existing.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** Names as typed when added, in insertion order. */
    public List<String> names() {
        return Collections.unmodifiableList(new ArrayList<>(names));
    }

    private void save() {
        List<String> lines = new ArrayList<>();
        if (names.isEmpty()) {
            lines.add("players: []");
        } else {
            lines.add("players:");
            for (String name : names) {
                lines.add("- \"" + escape(name) + "\"");
            }
        }
        try {
            Path parent = savePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(savePath, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warning("Failed to save allow list to " + savePath + ": " + e.getMessage());
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            String inner = value.substring(1, value.length() - 1);
            return inner.replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return value;
    }
}
