// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.config;

import net.rcwalter.ashlar.agent.Pricing;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
        EngineConfig engine,
        AgentConfig agent,
        String language
) {

    public record ServerConfig(String host, int port, String token, List<String> allowedIps) {
    }

    public record LimitsConfig(long maxBlocksPerOperation, long maxReadVolume, long tickBudgetMs,
            int maxQueuedOperations, int maxChunksPerOperation, long maxFlowingLiquidsPerOperation) {
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

    /**
     * The in-game AI assistant ({@code /ashlar}, step6a-prompt.md; embedded agent,
     * step8b-prompt.md). {@code mode}: {@link Mode#EMBEDDED} answers requests in-process,
     * {@link Mode#EXTERNAL} forwards them to a connected {@code ashlar-mcp --agent} process (0.2
     * behaviour), {@link Mode#OFF} disables the command entirely. {@code cooldownSeconds}: minimum
     * gap between two requests from the same player, may be 0. {@code maxMessageLength}: longest
     * request text accepted, must be at least 1. {@code echoToMonitors}: whether players with
     * {@code ashlar.monitor} see a compact echo of every request and final reply
     * (step6d-prompt.md). {@code everyoneCanUse}: whether the {@code ashlar.use} permission's
     * runtime default is set to "everyone" (true) or left at "op" (false) in
     * {@link net.rcwalter.ashlar.AshlarPlugin#onEnable}; daily limits and the cooldown still apply
     * either way (step6.6). {@code model}/{@code limits}/{@code pricing} only matter in
     * {@link Mode#EMBEDDED}.
     */
    public record AgentConfig(Mode mode, int cooldownSeconds, int maxMessageLength, boolean echoToMonitors,
            boolean everyoneCanUse, ModelConfig model, LimitsConfig limits, PricingConfig pricing) {

        public enum Mode {
            EMBEDDED, EXTERNAL, OFF;

            /** Parses {@code agent.mode}; anything unrecognised (including null/blank) falls back to {@link #EMBEDDED} with a warning. */
            public static Mode parse(String raw, Logger logger) {
                if (raw == null) {
                    return EMBEDDED;
                }
                return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                    case "embedded" -> EMBEDDED;
                    case "external" -> EXTERNAL;
                    case "off" -> OFF;
                    default -> {
                        logger.warning("Config value 'agent.mode' must be one of embedded/external/off (got '"
                                + raw + "'); falling back to default: embedded");
                        yield EMBEDDED;
                    }
                };
            }
        }

        /**
         * Connection to the OpenAI-compatible model API, only used in {@link Mode#EMBEDDED}.
         * {@code apiKey} may be empty (the assistant is then reported as "not configured" rather
         * than failing to start, step8b-prompt.md &sect;1). {@code baseUrl} never has a trailing
         * slash; {@code "/chat/completions"} is appended by {@link net.rcwalter.ashlar.agent.ModelClient}.
         */
        public record ModelConfig(String baseUrl, String apiKey, String model, int maxToolCalls,
                long requestTimeoutMs, String imageDetail, String systemPromptFile, boolean allowCommand) {
        }

        /** Per-player daily caps (0 = unlimited) and the embedded agent's concurrency/history settings. */
        public record LimitsConfig(int maxRequestsPerPlayerPerDay, long maxTokensPerPlayerPerDay,
                double maxCostPerPlayerPerDay, int maxConcurrent, int historyTurns, int historyTtlMinutes) {
        }

        /** Peak/off-peak pricing; {@code peakHours} is always a string {@link Pricing#parsePeakHours} accepts. */
        public record PricingConfig(double input, double cachedInput, double output, String currency,
                String peakHours, double offPeakMultiplier) {
        }
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
        String language = validateLanguage(fc.getString("language", "en"));

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
        long maxFlowingLiquidsPerOperation = positiveOrDefault(fc, "limits.max-flowing-liquids-per-operation", 2000, logger);

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

        AgentConfig.Mode agentMode = AgentConfig.Mode.parse(fc.getString("agent.mode", "embedded"), logger);
        int agentCooldownSeconds = (int) nonNegativeOrDefault(fc, "agent.cooldown-seconds", 5, logger);
        int agentMaxMessageLength = (int) positiveOrDefault(fc, "agent.max-message-length", 500, logger);
        boolean agentEchoToMonitors = fc.getBoolean("agent.echo-to-monitors", true);
        boolean agentEveryoneCanUse = fc.getBoolean("agent.everyone-can-use", false);

        String modelBaseUrl = stripTrailingSlashes(fc.getString("agent.model.base-url", "https://api.deepseek.com"));
        String modelApiKey = fc.getString("agent.model.api-key", "");
        String modelModel = fc.getString("agent.model.model", "deepseek-flash");
        int modelMaxToolCalls = (int) positiveOrDefault(fc, "agent.model.max-tool-calls", 25, logger);
        long modelRequestTimeoutMs = positiveOrDefault(fc, "agent.model.request-timeout-ms", 120_000, logger);
        String modelImageDetail = validateImageDetail(fc.getString("agent.model.image-detail", "high"), logger);
        String modelSystemPromptFile = fc.getString("agent.model.system-prompt-file", "");
        boolean modelAllowCommand = fc.getBoolean("agent.model.allow-command", false);

        int limitsMaxRequests = (int) nonNegativeOrDefault(fc, "agent.limits.max-requests-per-player-per-day", 40, logger);
        long limitsMaxTokens = nonNegativeOrDefault(fc, "agent.limits.max-tokens-per-player-per-day", 0, logger);
        double limitsMaxCost = nonNegativeDoubleOrDefault(fc, "agent.limits.max-cost-per-player-per-day", 0, logger);
        int limitsMaxConcurrent = (int) positiveOrDefault(fc, "agent.limits.max-concurrent", 2, logger);
        int limitsHistoryTurns = (int) positiveOrDefault(fc, "agent.limits.history-turns", 6, logger);
        int limitsHistoryTtlMinutes = (int) positiveOrDefault(fc, "agent.limits.history-ttl-minutes", 30, logger);

        double pricingInput = nonNegativeDoubleOrDefault(fc, "agent.pricing.input", 0.30, logger);
        double pricingCachedInput = nonNegativeDoubleOrDefault(fc, "agent.pricing.cached-input", 0.006, logger);
        double pricingOutput = nonNegativeDoubleOrDefault(fc, "agent.pricing.output", 1.20, logger);
        String pricingCurrency = fc.getString("agent.pricing.currency", "USD");
        String pricingPeakHours = validatePeakHours(
                fc.getString("agent.pricing.peak-hours", DEFAULT_PEAK_HOURS), logger);
        double pricingOffPeakMultiplier = nonNegativeDoubleOrDefault(fc, "agent.pricing.off-peak-multiplier", 0.5, logger);

        return new PluginConfig(
                new ServerConfig(host, port, token, List.copyOf(allowedIps)),
                new LimitsConfig(maxBlocksPerOperation, maxReadVolume, tickBudgetMs, maxQueuedOperations,
                        maxChunksPerOperation, maxFlowingLiquidsPerOperation),
                new WorldConfig(defaultWorld, List.copyOf(allowedWorlds),
                        new WorldConfig.BuildRegion(buildRegionEnabled, minX, minZ, maxX, maxZ)),
                new SnapshotConfig(snapshotEnabled, maxSnapshots, maxVolume),
                new LoggingConfig(logOperations),
                new RunCommandConfig(runCommandEnabled),
                new EngineConfig(connectBlocks, supportWarnings),
                new AgentConfig(agentMode, agentCooldownSeconds, agentMaxMessageLength, agentEchoToMonitors,
                        agentEveryoneCanUse,
                        new AgentConfig.ModelConfig(modelBaseUrl, modelApiKey, modelModel, modelMaxToolCalls,
                                modelRequestTimeoutMs, modelImageDetail, modelSystemPromptFile, modelAllowCommand),
                        new AgentConfig.LimitsConfig(limitsMaxRequests, limitsMaxTokens, limitsMaxCost,
                                limitsMaxConcurrent, limitsHistoryTurns, limitsHistoryTtlMinutes),
                        new AgentConfig.PricingConfig(pricingInput, pricingCachedInput, pricingOutput, pricingCurrency,
                                pricingPeakHours, pricingOffPeakMultiplier)),
                language);
    }

    static final String DEFAULT_PEAK_HOURS = "mon-fri 01:00-04:00,06:00-10:00";

    /**
     * {@code language}: {@code en}/{@code zh_CN}/{@code auto} (step8i-prompt.md), case-sensitive -
     * unlike most config values this is fatal on an invalid value rather than falling back with a
     * warning, since a typo here would otherwise silently ship the wrong language to every player.
     */
    static String validateLanguage(String raw) throws ConfigException {
        if ("en".equals(raw) || "zh_CN".equals(raw) || "auto".equals(raw)) {
            return raw;
        }
        throw new ConfigException(
                "'language' must be one of en, zh_CN, auto (got '" + raw + "').");
    }

    private static long positiveOrDefault(FileConfiguration fc, String path, long fallback, Logger logger) {
        return positiveOrDefaultValue(fc.getLong(path, fallback), fallback, path, logger);
    }

    /** Pure validation helper (no {@link FileConfiguration}), directly unit-testable. */
    static long positiveOrDefaultValue(long value, long fallback, String path, Logger logger) {
        if (value <= 0) {
            logger.warning("Config value '" + path + "' must be a positive integer; falling back to default: " + fallback);
            return fallback;
        }
        return value;
    }

    /** Like {@link #positiveOrDefault}, but 0 is a valid value (e.g. "no cooldown"); only negatives fall back. */
    private static long nonNegativeOrDefault(FileConfiguration fc, String path, long fallback, Logger logger) {
        return nonNegativeOrDefaultValue(fc.getLong(path, fallback), fallback, path, logger);
    }

    /** Pure validation helper (no {@link FileConfiguration}), directly unit-testable. */
    static long nonNegativeOrDefaultValue(long value, long fallback, String path, Logger logger) {
        if (value < 0) {
            logger.warning("Config value '" + path + "' must not be negative; falling back to default: " + fallback);
            return fallback;
        }
        return value;
    }

    private static double nonNegativeDoubleOrDefault(FileConfiguration fc, String path, double fallback, Logger logger) {
        return nonNegativeDoubleOrDefaultValue(fc.getDouble(path, fallback), fallback, path, logger);
    }

    /** Pure validation helper (no {@link FileConfiguration}), directly unit-testable. */
    static double nonNegativeDoubleOrDefaultValue(double value, double fallback, String path, Logger logger) {
        if (!Double.isFinite(value) || value < 0) {
            logger.warning("Config value '" + path + "' must be a non-negative number; falling back to default: " + fallback);
            return fallback;
        }
        return value;
    }

    /** Pure validation helper, directly unit-testable: {@code agent.model.image-detail} must be low/high/auto. */
    static String validateImageDetail(String raw, Logger logger) {
        if ("low".equals(raw) || "high".equals(raw) || "auto".equals(raw)) {
            return raw;
        }
        logger.warning("Config value 'agent.model.image-detail' must be 'low', 'high' or 'auto' (got '"
                + raw + "'); falling back to default: high");
        return "high";
    }

    /** Pure validation helper, directly unit-testable: falls back to {@link #DEFAULT_PEAK_HOURS} on a parse failure. */
    static String validatePeakHours(String raw, Logger logger) {
        try {
            Pricing.parsePeakHours(raw);
            return raw;
        } catch (IllegalArgumentException e) {
            logger.warning("Config value 'agent.pricing.peak-hours' is invalid (" + e.getMessage()
                    + "); falling back to default: " + DEFAULT_PEAK_HOURS);
            return DEFAULT_PEAK_HOURS;
        }
    }

    private static String stripTrailingSlashes(String url) {
        String result = url;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
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
