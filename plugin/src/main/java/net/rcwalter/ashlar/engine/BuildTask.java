// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.engine;

import com.google.gson.JsonElement;
import org.bukkit.World;

/**
 * A unit of world-mutating work executed incrementally across many ticks by
 * {@link TickBudgetExecutor}. Implementations must confine all Bukkit
 * {@code World}/{@code Block} access to {@link #step(long)}, which the
 * executor only ever calls from the main thread (plan.md &sect;2.3 point 1).
 */
public abstract class BuildTask {

    /** Fired as scanning progresses; used to relay {@code progress} events to the client. */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long done, long total);
    }

    /** How many scanned blocks/positions must pass between two {@code progress} events. */
    private static final long PROGRESS_INTERVAL = 2000;

    private final Region region;
    private long changed = 0;
    private long done = 0;
    private long lastReportedDone = 0;
    private ProgressListener progressListener = (d, t) -> { };

    protected BuildTask(Region region) {
        this.region = region;
    }

    /** The bounding region of this task, used to acquire chunk tickets before it runs. */
    public final Region region() {
        return region;
    }

    /** The world this task writes to; also used to acquire chunk tickets. */
    public abstract World world();

    /** Total number of blocks/positions this task will scan; the denominator of {@code progress} events. */
    public abstract long volume();

    /** Number of blocks/positions actually written so far (no-op writes are not counted). */
    public final long changed() {
        return changed;
    }

    /** Number of blocks/positions scanned so far; the numerator of {@code progress} events. */
    public final long done() {
        return done;
    }

    public final void setProgressListener(ProgressListener listener) {
        this.progressListener = listener == null ? (d, t) -> { } : listener;
    }

    /**
     * Does as much work as fits before {@code deadlineNanos} (checked at
     * least once per row of x, per plan.md &sect;2.1), then returns.
     * Returns {@code true} once the task has no work left; the executor
     * will call this again on a later tick if it returns {@code false}.
     */
    public abstract boolean step(long deadlineNanos);

    /**
     * Builds the RPC-protocol-specific result object once {@link #step} has
     * returned {@code true}. {@code queuedMs} is the time between submit and
     * the task actually starting; {@code elapsedMs} is execution time only
     * (plan.md &sect;2.6 follow-up fix). Not every result JSON surfaces both
     * (e.g. {@code heightmap}/{@code read_region}/{@code snapshot} report
     * neither, per spec &sect;3.2); implementations use only what their
     * protocol response documents.
     */
    public abstract JsonElement buildResult(long queuedMs, long elapsedMs);

    /** Adds to the running "actually written" counter. Call only for cells that were really written. */
    protected final void addChanged(long n) {
        changed += n;
    }

    /** Advances the scanned counter and fires a {@code progress} event every {@link #PROGRESS_INTERVAL} blocks. */
    protected final void advance(long n) {
        done += n;
        if (done - lastReportedDone >= PROGRESS_INTERVAL) {
            lastReportedDone = done;
            progressListener.onProgress(done, volume());
        }
    }

    /** Fires the final {@code progress} event ({@code done == total}). Called by the executor on completion. */
    public final void reportCompletion() {
        long total = volume();
        progressListener.onProgress(total, total);
    }
}
