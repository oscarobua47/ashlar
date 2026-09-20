// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import cc.wujm.ashlar.agent.model.Usage;
import cc.wujm.ashlar.i18n.Messages;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persists per-player token/cost usage, a rolling 90-day per-day history (step8g-prompt.md),
 * per-day limit overrides, per-player prepaid credit (step8f-prompt.md) and the global pause flag
 * (pure-Java port of {@code mcp-server/src/agent/usage.ts}, credit and day history added after
 * that file was retired in 0.4.0). Loaded synchronously at construction; saved atomically
 * ({@code <file>.tmp} then move), debounced, and on {@link #flush()} / {@link #close()}.
 */
public final class UsageStore implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger("Ashlar");
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final int HISTORY_DAYS_TO_KEEP = 90;

    public enum LimitKind {
        COST, TOKENS, REQUESTS;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static LimitKind fromWire(String s) {
            return switch (s) {
                case "cost" -> COST;
                case "tokens" -> TOKENS;
                case "requests" -> REQUESTS;
                default -> throw new IllegalArgumentException("unknown limit kind: " + s);
            };
        }
    }

    /** A concrete per-day cap, or {@link #OFF} for unlimited. */
    public static final class LimitValue {
        public static final LimitValue OFF = new LimitValue(true, 0);

        private final boolean off;
        private final double amount;

        private LimitValue(boolean off, double amount) {
            this.off = off;
            this.amount = amount;
        }

        public static LimitValue of(double amount) {
            return new LimitValue(false, amount);
        }

        public boolean isOff() {
            return off;
        }

        public double amount() {
            if (off) {
                throw new IllegalStateException("limit is off");
            }
            return amount;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof LimitValue other)) {
                return false;
            }
            return off == other.off && (off || amount == other.amount);
        }

        @Override
        public int hashCode() {
            return off ? -1 : Double.hashCode(amount);
        }

        @Override
        public String toString() {
            return off ? "off" : formatNumber(amount);
        }
    }

    /** Env-level per-day defaults; 0 means unlimited (matches the TS AgentConfig convention). */
    public record Limits(double cost, double tokens, double requests) {
    }

    public static final class DayCounters {
        public final String date;
        public long requests;
        public long inputTokens;
        public long cachedInputTokens;
        public long outputTokens;
        public double cost;

        public DayCounters(String date) {
            this.date = date;
        }

        DayCounters copy() {
            DayCounters c = new DayCounters(date);
            c.requests = requests;
            c.inputTokens = inputTokens;
            c.cachedInputTokens = cachedInputTokens;
            c.outputTokens = outputTokens;
            c.cost = cost;
            return c;
        }
    }

    public static final class TotalCounters {
        public long requests;
        public long inputTokens;
        public long cachedInputTokens;
        public long outputTokens;
        public double cost;

        TotalCounters copy() {
            TotalCounters c = new TotalCounters();
            c.requests = requests;
            c.inputTokens = inputTokens;
            c.cachedInputTokens = cachedInputTokens;
            c.outputTokens = outputTokens;
            c.cost = cost;
            return c;
        }
    }

    public record UsageSummary(String uuid, String name, DayCounters today, TotalCounters total,
                                Map<LimitKind, LimitValue> limits, Map<LimitKind, Boolean> overrides,
                                Optional<Credit> credit) {
    }

    /** {@link #history} / {@link #historyAll}: one row per day in the requested range (missing days are zero rows) plus the range's totals. */
    public record HistoryResult(List<DayCounters> days, TotalCounters totals) {
    }

    /** A player's prepaid balance; absent from the JSON store entirely when the player has none. */
    public record Credit(boolean enabled, double balance) {
    }

    public record RecordResult(double cost, DayCounters today, TotalCounters total) {
    }

    public record CheckResult(boolean ok, String reason) {
        public static CheckResult allowed() {
            return new CheckResult(true, null);
        }

        public static CheckResult rejected(String reason) {
            return new CheckResult(false, reason);
        }
    }

    public record NameMatch(String uuid, String name) {
    }

    private static final class PlayerRecord {
        String name;
        final Map<LimitKind, LimitValue> limits = new EnumMap<>(LimitKind.class);
        DayCounters today;
        TotalCounters total = new TotalCounters();
        /** UTC date -> that day's counters; keeps {@code today}'s date once it stops being "today" (step8g-prompt.md). */
        final Map<String, DayCounters> days = new HashMap<>();
        long updatedAt;
        Credit credit;
    }

    private final Path filePath;
    private final double priceInput;
    private final double priceCachedInput;
    private final double priceOutput;
    private final String currency;
    private final Limits envLimits;
    private final Pricing.Schedule peakSchedule;
    private final double offPeakMultiplier;
    private final Supplier<Instant> now;
    private final long saveDebounceMs;

    private final Object lock = new Object();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ashlar-usage-save");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> pendingSave;
    private boolean dirty;

    private boolean paused;
    private final Map<LimitKind, LimitValue> defaults = new EnumMap<>(LimitKind.class);
    private final Map<String, PlayerRecord> players = new HashMap<>();

    public UsageStore(Path filePath, double priceInput, double priceCachedInput, double priceOutput, String currency,
                       Limits envLimits, Pricing.Schedule peakSchedule, double offPeakMultiplier) {
        this(filePath, priceInput, priceCachedInput, priceOutput, currency, envLimits, peakSchedule, offPeakMultiplier,
                Instant::now, 500);
    }

    public UsageStore(Path filePath, double priceInput, double priceCachedInput, double priceOutput, String currency,
                       Limits envLimits, Pricing.Schedule peakSchedule, double offPeakMultiplier,
                       Supplier<Instant> now, long saveDebounceMs) {
        this.filePath = filePath;
        this.priceInput = priceInput;
        this.priceCachedInput = priceCachedInput;
        this.priceOutput = priceOutput;
        this.currency = currency;
        this.envLimits = envLimits;
        this.peakSchedule = peakSchedule;
        this.offPeakMultiplier = offPeakMultiplier;
        this.now = now != null ? now : Instant::now;
        this.saveDebounceMs = saveDebounceMs;
        load();
    }

    private static String formatNumber(double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d)) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    private String todayStr() {
        return DAY_FORMAT.format(now.get());
    }

    private static DayCounters freshDay(String date) {
        return new DayCounters(date);
    }

    // ---- load / save ----

    private void load() {
        String raw;
        try {
            raw = Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return; // Missing file: empty store.
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, () -> "[agent-usage] could not read " + filePath + ": " + e.getMessage() + "; starting empty");
            return;
        }

        JsonObject parsed;
        try {
            JsonElement el = JsonParser.parseString(raw);
            if (!el.isJsonObject()) {
                backupCorruptFile("unexpected shape");
                return;
            }
            parsed = el.getAsJsonObject();
        } catch (RuntimeException e) {
            backupCorruptFile("invalid JSON: " + e.getMessage());
            return;
        }

        if (!isValidStoreFile(parsed)) {
            backupCorruptFile("unexpected shape");
            return;
        }

        paused = parsed.get("paused").getAsBoolean();
        defaults.clear();
        JsonObject defaultsJson = parsed.getAsJsonObject("defaults");
        for (LimitKind kind : LimitKind.values()) {
            LimitValue v = readLimitValue(defaultsJson, kind.wire());
            if (v != null) {
                defaults.put(kind, v);
            }
        }

        players.clear();
        JsonObject playersJson = parsed.getAsJsonObject("players");
        for (Map.Entry<String, JsonElement> entry : playersJson.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject rec = entry.getValue().getAsJsonObject();
            if (!rec.has("name") || !rec.get("name").isJsonPrimitive() || !rec.has("today") || !rec.has("total")) {
                continue;
            }
            PlayerRecord pr = new PlayerRecord();
            pr.name = rec.get("name").getAsString();
            JsonObject limitsJson = rec.has("limits") && rec.get("limits").isJsonObject() ? rec.getAsJsonObject("limits") : null;
            for (LimitKind kind : LimitKind.values()) {
                LimitValue v = readLimitValue(limitsJson, kind.wire());
                if (v != null) {
                    pr.limits.put(kind, v);
                }
            }
            pr.today = readDayCounters(rec.getAsJsonObject("today"), todayStr());
            pr.total = readTotalCounters(rec.getAsJsonObject("total"));
            pr.updatedAt = rec.has("updatedAt") && rec.get("updatedAt").isJsonPrimitive() ? rec.get("updatedAt").getAsLong() : 0;
            pr.credit = readCredit(rec);
            if (rec.has("days") && rec.get("days").isJsonObject()) {
                for (Map.Entry<String, JsonElement> dayEntry : rec.getAsJsonObject("days").entrySet()) {
                    if (dayEntry.getValue().isJsonObject()) {
                        pr.days.put(dayEntry.getKey(), readDayCounters(dayEntry.getValue().getAsJsonObject(), dayEntry.getKey()));
                    }
                }
            }
            // Migration (step8g-prompt.md): an old-format record has no "days" entry for its own
            // "today" date - a file saved before this feature existed. Back-fill it so the player
            // gains one day of history from the day the file was last written, instead of a gap.
            if (!pr.days.containsKey(pr.today.date)) {
                pr.days.put(pr.today.date, pr.today.copy());
            }
            players.put(entry.getKey(), pr);
        }
    }

    private static boolean isLimitValueJson(JsonElement v) {
        if (v == null || v.isJsonNull() || !v.isJsonPrimitive()) {
            return false;
        }
        JsonPrimitive p = v.getAsJsonPrimitive();
        if (p.isString()) {
            return "off".equals(p.getAsString());
        }
        if (p.isNumber()) {
            double d = p.getAsDouble();
            return Double.isFinite(d) && d > 0;
        }
        return false;
    }

    private static LimitValue readLimitValue(JsonObject o, String field) {
        if (o == null || !o.has(field)) {
            return null;
        }
        JsonElement v = o.get(field);
        if (!isLimitValueJson(v)) {
            return null;
        }
        JsonPrimitive p = v.getAsJsonPrimitive();
        return p.isString() ? LimitValue.OFF : LimitValue.of(p.getAsDouble());
    }

    private static DayCounters readDayCounters(JsonObject o, String fallbackDate) {
        DayCounters d = new DayCounters(o != null && o.has("date") ? o.get("date").getAsString() : fallbackDate);
        if (o != null) {
            d.requests = longOr(o, "requests", 0);
            d.inputTokens = longOr(o, "inputTokens", 0);
            d.cachedInputTokens = longOr(o, "cachedInputTokens", 0);
            d.outputTokens = longOr(o, "outputTokens", 0);
            d.cost = doubleOr(o, "cost", 0);
        }
        return d;
    }

    private static TotalCounters readTotalCounters(JsonObject o) {
        TotalCounters t = new TotalCounters();
        if (o != null) {
            t.requests = longOr(o, "requests", 0);
            t.inputTokens = longOr(o, "inputTokens", 0);
            t.cachedInputTokens = longOr(o, "cachedInputTokens", 0);
            t.outputTokens = longOr(o, "outputTokens", 0);
            t.cost = doubleOr(o, "cost", 0);
        }
        return t;
    }

    private static Credit readCredit(JsonObject rec) {
        if (!rec.has("credit") || !rec.get("credit").isJsonObject()) {
            return null;
        }
        JsonObject c = rec.getAsJsonObject("credit");
        boolean enabled = c.has("enabled") && c.get("enabled").isJsonPrimitive() && c.get("enabled").getAsJsonPrimitive().isBoolean()
                && c.get("enabled").getAsBoolean();
        double balance = doubleOr(c, "balance", 0);
        return new Credit(enabled, balance);
    }

    private static long longOr(JsonObject o, String field, long fallback) {
        return o.has(field) && o.get(field).isJsonPrimitive() ? o.get(field).getAsLong() : fallback;
    }

    private static double doubleOr(JsonObject o, String field, double fallback) {
        return o.has(field) && o.get(field).isJsonPrimitive() ? o.get(field).getAsDouble() : fallback;
    }

    private static boolean isValidStoreFile(JsonObject o) {
        if (!o.has("version") || o.get("version").getAsInt() != 1) {
            return false;
        }
        if (!o.has("paused") || !o.get("paused").isJsonPrimitive() || !o.get("paused").getAsJsonPrimitive().isBoolean()) {
            return false;
        }
        if (!o.has("defaults") || !o.get("defaults").isJsonObject()) {
            return false;
        }
        return o.has("players") && o.get("players").isJsonObject();
    }

    private void backupCorruptFile(String reason) {
        Path backupPath = filePath.resolveSibling(filePath.getFileName() + ".corrupt-" + System.currentTimeMillis());
        LOGGER.log(Level.WARNING, () -> "[agent-usage] " + filePath + " is corrupt (" + reason + "); backing up to " + backupPath + " and starting empty");
        try {
            Files.move(filePath, backupPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, () -> "[agent-usage] could not back up corrupt file: " + e.getMessage());
        }
        paused = false;
        defaults.clear();
        players.clear();
    }

    private void scheduleSave() {
        synchronized (lock) {
            dirty = true;
            if (pendingSave != null) {
                return;
            }
            pendingSave = scheduler.schedule(this::saveFromTimer, saveDebounceMs, TimeUnit.MILLISECONDS);
        }
    }

    private void saveFromTimer() {
        synchronized (lock) {
            pendingSave = null;
        }
        saveNow();
    }

    private void saveNow() {
        synchronized (lock) {
            if (!dirty) {
                return;
            }
            dirty = false;

            String cutoffExclusive = DAY_FORMAT.format(now.get().minus(HISTORY_DAYS_TO_KEEP, ChronoUnit.DAYS));
            for (PlayerRecord rec : players.values()) {
                pruneOldDays(rec, cutoffExclusive);
            }

            JsonObject file = new JsonObject();
            file.addProperty("version", 1);
            file.addProperty("paused", paused);
            file.add("defaults", limitsToJson(defaults));
            JsonObject playersJson = new JsonObject();
            for (Map.Entry<String, PlayerRecord> e : players.entrySet()) {
                playersJson.add(e.getKey(), playerToJson(e.getValue()));
            }
            file.add("players", playersJson);

            String json = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(file);
            try {
                Files.createDirectories(filePath.toAbsolutePath().getParent());
                Path tmp = filePath.resolveSibling(filePath.getFileName() + ".tmp");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, () -> "[agent-usage] could not save " + filePath + ": " + e.getMessage());
            }
        }
    }

    private static JsonObject limitsToJson(Map<LimitKind, LimitValue> limits) {
        JsonObject o = new JsonObject();
        for (Map.Entry<LimitKind, LimitValue> e : limits.entrySet()) {
            o.add(e.getKey().wire(), e.getValue().isOff() ? new JsonPrimitive("off") : new JsonPrimitive(e.getValue().amount()));
        }
        return o;
    }

    private static JsonObject dayCountersToJson(DayCounters d) {
        JsonObject o = new JsonObject();
        o.addProperty("date", d.date);
        o.addProperty("requests", d.requests);
        o.addProperty("inputTokens", d.inputTokens);
        o.addProperty("cachedInputTokens", d.cachedInputTokens);
        o.addProperty("outputTokens", d.outputTokens);
        o.addProperty("cost", d.cost);
        return o;
    }

    private static JsonObject totalCountersToJson(TotalCounters t) {
        JsonObject o = new JsonObject();
        o.addProperty("requests", t.requests);
        o.addProperty("inputTokens", t.inputTokens);
        o.addProperty("cachedInputTokens", t.cachedInputTokens);
        o.addProperty("outputTokens", t.outputTokens);
        o.addProperty("cost", t.cost);
        return o;
    }

    private static JsonObject playerToJson(PlayerRecord rec) {
        JsonObject o = new JsonObject();
        o.addProperty("name", rec.name);
        o.add("limits", limitsToJson(rec.limits));
        o.add("today", dayCountersToJson(rec.today));
        o.add("total", totalCountersToJson(rec.total));
        JsonObject daysJson = new JsonObject();
        for (Map.Entry<String, DayCounters> e : new TreeMap<>(rec.days).entrySet()) {
            daysJson.add(e.getKey(), dayCountersToJson(e.getValue()));
        }
        o.add("days", daysJson);
        o.addProperty("updatedAt", rec.updatedAt);
        if (rec.credit != null) {
            JsonObject c = new JsonObject();
            c.addProperty("enabled", rec.credit.enabled());
            c.addProperty("balance", rec.credit.balance());
            o.add("credit", c);
        }
        return o;
    }

    /** Drops day entries older than {@link #HISTORY_DAYS_TO_KEEP} days, called just before every save. */
    private static void pruneOldDays(PlayerRecord rec, String cutoffExclusive) {
        rec.days.keySet().removeIf(date -> date.compareTo(cutoffExclusive) < 0);
    }

    /** Flushes any pending save synchronously without stopping future debounced saves. */
    public void flush() {
        ScheduledFuture<?> pending;
        synchronized (lock) {
            pending = pendingSave;
            pendingSave = null;
        }
        if (pending != null) {
            pending.cancel(false);
        }
        saveNow();
    }

    /** Flushes any pending save synchronously. Call once at shutdown. */
    @Override
    public void close() {
        flush();
        scheduler.shutdownNow();
    }

    // ---- rollover / player lookup ----

    private void rollover(PlayerRecord rec) {
        String day = todayStr();
        if (!rec.today.date.equals(day)) {
            rec.today = freshDay(day);
        }
    }

    private PlayerRecord getOrCreatePlayer(String uuid, String name) {
        PlayerRecord rec = players.get(uuid);
        if (rec == null) {
            rec = new PlayerRecord();
            rec.name = name;
            rec.today = freshDay(todayStr());
            rec.updatedAt = now.get().toEpochMilli();
            players.put(uuid, rec);
        } else {
            rec.name = name;
            rollover(rec);
        }
        return rec;
    }

    private LimitValue resolveLimit(LimitValue playerValue, LimitValue defaultValue, double envValue) {
        if (playerValue != null) {
            return playerValue;
        }
        if (defaultValue != null) {
            return defaultValue;
        }
        return envValue == 0 ? LimitValue.OFF : LimitValue.of(envValue);
    }

    /** The effective per-day cap for {@code uuid} and {@code kind}: player override > store default > env default. */
    public LimitValue effectiveLimit(String uuid, LimitKind kind) {
        PlayerRecord rec = players.get(uuid);
        double envValue = switch (kind) {
            case COST -> envLimits.cost();
            case TOKENS -> envLimits.tokens();
            case REQUESTS -> envLimits.requests();
        };
        return resolveLimit(rec != null ? rec.limits.get(kind) : null, defaults.get(kind), envValue);
    }

    /** All three effective limits at once, for {@code uuid}, or for the server default when {@code uuid} is null. */
    public Map<LimitKind, LimitValue> effectiveLimits(String uuid) {
        Map<LimitKind, LimitValue> result = new EnumMap<>(LimitKind.class);
        for (LimitKind kind : LimitKind.values()) {
            result.put(kind, uuid == null
                    ? resolveLimit(null, defaults.get(kind), envValueOf(kind))
                    : effectiveLimit(uuid, kind));
        }
        return result;
    }

    private double envValueOf(LimitKind kind) {
        return switch (kind) {
            case COST -> envLimits.cost();
            case TOKENS -> envLimits.tokens();
            case REQUESTS -> envLimits.requests();
        };
    }

    /**
     * Whether {@code uuid} may start a new request right now, per today's counters against the
     * effective limits. Never called mid-request: a running request is never interrupted by a
     * limit newly being reached.
     */
    public CheckResult checkAllowed(String uuid) {
        return checkAllowed(uuid, Messages.DEFAULT_LANGUAGE);
    }

    /**
     * Same as {@link #checkAllowed(String)}, but the rejection reason (if any) is translated into
     * {@code language} (step8i-prompt.md); {@code language = "en"} produces byte-identical text to
     * the single-argument overload above.
     */
    public CheckResult checkAllowed(String uuid, String language) {
        PlayerRecord rec = players.get(uuid);
        if (rec != null) {
            rollover(rec);
        }
        DayCounters today = rec != null ? rec.today : freshDay(todayStr());
        Messages messages = Messages.instance();

        LimitValue requestsLimit = effectiveLimit(uuid, LimitKind.REQUESTS);
        if (!requestsLimit.isOff() && today.requests >= requestsLimit.amount()) {
            return CheckResult.rejected(messages.get(language, "usage.limit.requests", formatNumber(requestsLimit.amount())));
        }

        LimitValue tokensLimit = effectiveLimit(uuid, LimitKind.TOKENS);
        if (!tokensLimit.isOff()) {
            long usedTokens = today.inputTokens + today.cachedInputTokens + today.outputTokens;
            if (usedTokens >= tokensLimit.amount()) {
                return CheckResult.rejected(messages.get(language, "usage.limit.tokens", fmtTokens(tokensLimit.amount())));
            }
        }

        LimitValue costLimit = effectiveLimit(uuid, LimitKind.COST);
        if (!costLimit.isOff() && today.cost >= costLimit.amount()) {
            return CheckResult.rejected(messages.get(language, "usage.limit.cost", fmtCost(costLimit.amount(), currency)));
        }

        if (rec != null && rec.credit != null && rec.credit.enabled() && rec.credit.balance() <= 0) {
            return CheckResult.rejected(messages.get(language, "usage.limit.credit", fmtCredit(rec.credit.balance(), currency)));
        }

        return CheckResult.allowed();
    }

    /** Adds one request's usage to {@code uuid}'s today/total counters and computes its cost at the current price multiplier. */
    public RecordResult record(String uuid, String name, Usage usage) {
        PlayerRecord rec = getOrCreatePlayer(uuid, name);
        double multiplier = Pricing.multiplier(peakSchedule, offPeakMultiplier, now.get());
        double cost = Pricing.cost(usage, new Pricing.Prices(priceInput, priceCachedInput, priceOutput), multiplier);

        rec.today.requests += 1;
        rec.today.inputTokens += usage.inputTokens();
        rec.today.cachedInputTokens += usage.cachedInputTokens();
        rec.today.outputTokens += usage.outputTokens();
        rec.today.cost += cost;

        DayCounters day = rec.days.computeIfAbsent(rec.today.date, DayCounters::new);
        day.requests += 1;
        day.inputTokens += usage.inputTokens();
        day.cachedInputTokens += usage.cachedInputTokens();
        day.outputTokens += usage.outputTokens();
        day.cost += cost;

        rec.total.requests += 1;
        rec.total.inputTokens += usage.inputTokens();
        rec.total.cachedInputTokens += usage.cachedInputTokens();
        rec.total.outputTokens += usage.outputTokens();
        rec.total.cost += cost;

        if (rec.credit != null && rec.credit.enabled()) {
            // Deducted without a floor: may go slightly negative on the turn that empties it,
            // which is exactly the signal AgentRunner's wrapUp supplier watches for; display
            // (fmtCredit) clamps a negative balance to 0.
            rec.credit = new Credit(true, rec.credit.balance() - cost);
        }

        rec.updatedAt = now.get().toEpochMilli();
        scheduleSave();

        return new RecordResult(cost, rec.today.copy(), rec.total.copy());
    }

    /**
     * Adds {@code amount} to {@code uuid}'s credit balance (enabling credit if it was not already
     * enabled), clamped so a correction never pushes the balance below 0; returns the new balance.
     * {@code name} is used only when creating a not-yet-seen player.
     */
    public double addCredit(String uuid, String name, double amount) {
        PlayerRecord rec = getOrCreatePlayer(uuid, name);
        double current = rec.credit != null ? rec.credit.balance() : 0;
        double newBalance = Math.max(0, current + amount);
        rec.credit = new Credit(true, newBalance);
        rec.updatedAt = now.get().toEpochMilli();
        scheduleSave();
        return newBalance;
    }

    /** Sets {@code uuid}'s credit balance outright (enabling credit if it was not already enabled). {@code name} is used only when creating a not-yet-seen player. */
    public void setCredit(String uuid, String name, double amount) {
        PlayerRecord rec = getOrCreatePlayer(uuid, name);
        rec.credit = new Credit(true, amount);
        rec.updatedAt = now.get().toEpochMilli();
        scheduleSave();
    }

    /** Disables {@code uuid}'s credit; a not-yet-seen player has nothing to disable. */
    public void disableCredit(String uuid) {
        PlayerRecord rec = players.get(uuid);
        if (rec == null || rec.credit == null) {
            return;
        }
        rec.credit = null;
        rec.updatedAt = now.get().toEpochMilli();
        scheduleSave();
    }

    /** {@code uuid}'s credit, if enabled. */
    public Optional<Credit> creditOf(String uuid) {
        PlayerRecord rec = players.get(uuid);
        return rec != null && rec.credit != null ? Optional.of(rec.credit) : Optional.empty();
    }

    /** Sets a per-day cap for {@code uuid}, or the store default when {@code uuid} is null. {@code name} is used only when creating a not-yet-seen player. */
    public void setLimit(String uuid, LimitKind kind, LimitValue value, String name) {
        if (uuid == null) {
            defaults.put(kind, value);
        } else {
            PlayerRecord rec = getOrCreatePlayer(uuid, name != null ? name : Optional.ofNullable(players.get(uuid)).map(r -> r.name).orElse(uuid));
            rec.limits.put(kind, value);
        }
        scheduleSave();
    }

    /** Removes all limit overrides for {@code uuid}, or all store defaults when {@code uuid} is null. */
    public void resetLimits(String uuid) {
        if (uuid == null) {
            defaults.clear();
        } else {
            PlayerRecord rec = players.get(uuid);
            if (rec != null) {
                rec.limits.clear();
            }
        }
        scheduleSave();
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        scheduleSave();
    }

    public boolean isPaused() {
        return paused;
    }

    /** Case-insensitive lookup by the last-seen name for that uuid (ties broken by most recently updated). */
    public Optional<NameMatch> findByName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        NameMatch best = null;
        long bestUpdatedAt = Long.MIN_VALUE;
        for (Map.Entry<String, PlayerRecord> e : players.entrySet()) {
            PlayerRecord rec = e.getValue();
            if (!rec.name.toLowerCase(Locale.ROOT).equals(lower)) {
                continue;
            }
            if (best == null || rec.updatedAt > bestUpdatedAt) {
                best = new NameMatch(e.getKey(), rec.name);
                bestUpdatedAt = rec.updatedAt;
            }
        }
        return Optional.ofNullable(best);
    }

    /** A usage summary for {@code uuid}, synthesising zero counters if no request has been recorded yet. */
    public UsageSummary summary(String uuid, String nameFallback) {
        PlayerRecord rec = players.get(uuid);
        if (rec != null) {
            rollover(rec);
        }
        Map<LimitKind, Boolean> overrides = new EnumMap<>(LimitKind.class);
        for (LimitKind kind : LimitKind.values()) {
            overrides.put(kind, rec != null && rec.limits.containsKey(kind));
        }
        return new UsageSummary(
                uuid,
                rec != null ? rec.name : (nameFallback != null ? nameFallback : uuid),
                rec != null ? rec.today.copy() : freshDay(todayStr()),
                rec != null ? rec.total.copy() : new TotalCounters(),
                effectiveLimits(uuid),
                overrides,
                rec != null ? Optional.ofNullable(rec.credit) : Optional.empty());
    }

    public UsageSummary summary(String uuid) {
        return summary(uuid, null);
    }

    /** Every known player's summary, sorted by today's cost descending. */
    public List<UsageSummary> summaryAll() {
        List<UsageSummary> all = new ArrayList<>();
        for (String uuid : players.keySet()) {
            all.add(summary(uuid));
        }
        all.sort(Comparator.comparingDouble((UsageSummary s) -> s.today().cost).reversed());
        return all;
    }

    /** Today's UTC date, {@code yyyy-MM-dd} - the "ending today" anchor for a day-count range (step8g-prompt.md). */
    public String todayIso() {
        return todayStr();
    }

    /**
     * {@code uuid}'s usage for each UTC date in {@code [from, to]} inclusive - a zero row for any
     * day with no recorded activity, so the caller gets exactly one line per day - plus the
     * summed {@link TotalCounters} for the whole range.
     */
    public HistoryResult history(String uuid, LocalDate from, LocalDate to) {
        PlayerRecord rec = players.get(uuid);
        if (rec != null) {
            rollover(rec);
        }
        List<DayCounters> rows = new ArrayList<>();
        TotalCounters totals = new TotalCounters();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            String date = DAY_FORMAT.format(d.atStartOfDay(ZoneOffset.UTC).toInstant());
            DayCounters counters = rec != null && rec.days.containsKey(date) ? rec.days.get(date).copy() : freshDay(date);
            rows.add(counters);
            totals.requests += counters.requests;
            totals.inputTokens += counters.inputTokens;
            totals.cachedInputTokens += counters.cachedInputTokens;
            totals.outputTokens += counters.outputTokens;
            totals.cost += counters.cost;
        }
        return new HistoryResult(rows, totals);
    }

    /** Same as {@link #history} but summed over every known player, for {@code usage all <range>}. */
    public HistoryResult historyAll(LocalDate from, LocalDate to) {
        for (PlayerRecord rec : players.values()) {
            rollover(rec);
        }
        List<DayCounters> rows = new ArrayList<>();
        TotalCounters totals = new TotalCounters();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            String date = DAY_FORMAT.format(d.atStartOfDay(ZoneOffset.UTC).toInstant());
            DayCounters counters = freshDay(date);
            for (PlayerRecord rec : players.values()) {
                DayCounters playerDay = rec.days.get(date);
                if (playerDay != null) {
                    counters.requests += playerDay.requests;
                    counters.inputTokens += playerDay.inputTokens;
                    counters.cachedInputTokens += playerDay.cachedInputTokens;
                    counters.outputTokens += playerDay.outputTokens;
                    counters.cost += playerDay.cost;
                }
            }
            rows.add(counters);
            totals.requests += counters.requests;
            totals.inputTokens += counters.inputTokens;
            totals.cachedInputTokens += counters.cachedInputTokens;
            totals.outputTokens += counters.outputTokens;
            totals.cost += counters.cost;
        }
        return new HistoryResult(rows, totals);
    }

    /** {@code 21.9k}, {@code 1.2M}, plain integer below 1000; trailing {@code .0} is dropped ({@code 500000} -> {@code 500k}). */
    public static String fmtTokens(double n) {
        if (n < 1000) {
            return String.valueOf(Math.round(n));
        }
        boolean useMillions = n >= 1_000_000;
        double scaled = n / (useMillions ? 1_000_000.0 : 1000.0);
        String formatted = String.format(Locale.ROOT, "%.1f", scaled);
        String trimmed = formatted.endsWith(".0") ? formatted.substring(0, formatted.length() - 2) : formatted;
        return trimmed + (useMillions ? "M" : "k");
    }

    /** 2 decimals normally, 4 when the amount is a nonzero value below 0.01; {@code currency} is {@code $}-prefixed for USD, {@code <code> }-prefixed otherwise. */
    public static String fmtCost(double amount, String currency) {
        int decimals = (amount > 0 && amount < 0.01) ? 4 : 2;
        String formatted = String.format(Locale.ROOT, "%." + decimals + "f", amount);
        return "USD".equals(currency) ? "$" + formatted : currency + " " + formatted;
    }

    /** Same rendering as {@link #fmtCost}, but a negative balance (the last turn overdrawing it) shows as 0. */
    public static String fmtCredit(double balance, String currency) {
        return fmtCost(Math.max(0, balance), currency);
    }
}
