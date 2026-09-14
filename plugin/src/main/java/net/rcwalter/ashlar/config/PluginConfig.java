// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Immutable, validated configuration loaded from {@code config.yml}.
 * Validation follows plan.md &sect;1.3: {@code server.token}, {@code
 * server.port} and {@code server.allowed-ips} are fatal (throw {@link
 * ConfigException}, the plugin then disables itself); {@code limits.*} and
 * {@code world.allowed-worlds} fall back to defaults with a warning.
 */
public record PluginConfig(
        ServerConfig server,
        LimitsConfig limits,
        WorldConfig world,
        SnapshotConfig snapshot,
        LoggingConfig logging,
        RunCommandConfig runCommand,
        EngineConfig engine
) {

    public record ServerConfig(String host, int port, String token, List<String> allowedIps) {
    }

    public record LimitsConfig(long maxBlocksPerOperation, long maxReadVolume, long tickBudgetMs,
            int maxQueuedOperations, int maxChunksPerOperation) {
    }

    public record WorldConfig(String defaultWorld, List<String> allowedWorlds, BuildRegion buildRegion) {
        public record BuildRegion(boolean enabled, int minX, int minZ, int maxX, int maxZ) {
        }
    }

    public record SnapshotConfig(boolean enabled, int maxSnapshots, long maxVolume) {
    }

    public record LoggingConfig(boolean logOperations) {
    }

    public record RunCommandConfig(boolean enabled) {
    }

    /**
     * {@code connect-blocks}: whether the Fix 2 connection pass runs by default (step4d-prompt.md).
     * {@code supportWarnings}: whether the post-build support check (step4h-prompt.md) runs at all;
     * unlike {@code connect-blocks} this has no per-request override.
     */
    public record EngineConfig(boolean connectBlocks, boolean supportWarnings) {
    }

    /** Empty allow-list means "allow all", per spec &sect;3.1. */
    public boolean isIpAllowed(String ip) {
        return server.allowedIps().isEmpty() || server.allowedIps().contains(ip);
    }

    private static final Pattern IPV4 = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");
    // v1 only needs exact IPv4/IPv6 addresses (CIDR is deferred to v1.1, plan.md 1.5).
    // This loose IPv6 check accepts any hex-and-colon literal with at least two colons.
    private static final Pattern IPV6_LOOSE = Pattern.compile("^[0-9a-fA-F:]+$");

    public static PluginConfig load(FileConfiguration fc, Logger logger) throws ConfigException {
        String token = fc.getString("server.token", "");
        if (token == null || token.trim().length() < 16) {
            throw new ConfigException(
                    "server.token is empty or too short (must be at least 16 characters). Refusing to start.");
        }

        int port = fc.getInt("server.port", 8765);
        if (port < 1 || port > 65535) {
            throw new ConfigException("server.port must be between 1 and 65535, got: " + port);
        }

        String host = fc.getString("server.host", "0.0.0.0");

        List<String> allowedIps = new ArrayList<>();
        for (String ip : fc.getStringList("server.allowed-ips")) {
            if (!isValidIp(ip)) {
                throw new ConfigException(
                        "server.allowed-ips contains an invalid entry: '" + ip
                                + "' (v1 only supports exact IPv4/IPv6 addresses, not hostnames or CIDR).");
            }
            allowedIps.add(ip);
        }

        long maxBlocksPerOperation = positiveOrDefault(fc, "limits.max-blocks-per-operation", 500_000, logger);
        long maxReadVolume = positiveOrDefault(fc, "limits.max-read-volume", 200_000, logger);
        long tickBudgetMs = positiveOrDefault(fc, "limits.tick-budget-ms", 20, logger);
        int maxQueuedOperations = (int) positiveOrDefault(fc, "limits.max-queued-operations", 16, logger);
        int maxChunksPerOperation = (int) positiveOrDefault(fc, "limits.max-chunks-per-operation", 1024, logger);

        String defaultWorld = fc.getString("world.default", "world");
        List<String> allowedWorlds = new ArrayList<>(fc.getStringList("world.allowed-worlds"));
        if (allowedWorlds.isEmpty()) {
            logger.warning("world.allowed-worlds is empty; falling back to ['" + defaultWorld + "']");
            allowedWorlds.add(defaultWorld);
        }

        boolean buildRegionEnabled = fc.getBoolean("world.build-region.enabled", false);
        int minX = fc.getInt("world.build-region.min.x", -1000);
        int minZ = fc.getInt("world.build-region.min.z", -1000);
        int maxX = fc.getInt("world.build-region.max.x", 1000);
        int maxZ = fc.getInt("world.build-region.max.z", 1000);

        boolean snapshotEnabled = fc.getBoolean("snapshot.enabled", true);
        int maxSnapshots = (int) positiveOrDefault(fc, "snapshot.max-snapshots", 20, logger);
        long maxVolume = positiveOrDefault(fc, "snapshot.max-volume", 200_000, logger);

        boolean logOperations = fc.getBoolean("logging.log-operations", true);
        boolean runCommandEnabled = fc.getBoolean("run-command.enabled", true);
        boolean connectBlocks = fc.getBoolean("engine.connect-blocks", true);
        boolean supportWarnings = fc.getBoolean("engine.support-warnings", true);

        return new PluginConfig(
                new ServerConfig(host, port, token, List.copyOf(allowedIps)),
                new LimitsConfig(maxBlocksPerOperation, maxReadVolume, tickBudgetMs, maxQueuedOperations, maxChunksPerOperation),
                new WorldConfig(defaultWorld, List.copyOf(allowedWorlds),
                        new WorldConfig.BuildRegion(buildRegionEnabled, minX, minZ, maxX, maxZ)),
                new SnapshotConfig(snapshotEnabled, maxSnapshots, maxVolume),
                new LoggingConfig(logOperations),
                new RunCommandConfig(runCommandEnabled),
                new EngineConfig(connectBlocks, supportWarnings));
    }

    private static long positiveOrDefault(FileConfiguration fc, String path, long fallback, Logger logger) {
        long value = fc.getLong(path, fallback);
        if (value <= 0) {
            logger.warning("Config value '" + path + "' must be a positive integer; falling back to default: " + fallback);
            return fallback;
        }
        return value;
    }

    private static boolean isValidIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        if (IPV4.matcher(ip).matches()) {
            return true;
        }
        // Require at least two colons so we don't accept a bare hex word as "IPv6".
        return ip.contains(":") && ip.chars().filter(c -> c == ':').count() >= 2 && IPV6_LOOSE.matcher(ip).matches();
    }
}
