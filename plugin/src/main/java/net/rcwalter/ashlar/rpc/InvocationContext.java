// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.rpc;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Decouples execution from the transport that requested it (plan.md step7:
 * the tool layer and, later, an in-process agent call the same execution
 * paths RPC handlers use today, but from inside the JVM with no WebSocket
 * session). Carries who is asking ({@link #principal()}), an id for tagging
 * {@code progress} events, a place to send those events, cooperative
 * cancellation, and an optional deadline.
 *
 * <p>Immutable except the cancel flag, which is the only field ever mutated
 * after construction.
 */
public final class InvocationContext {

    public enum Kind { WS_TOKEN, PLAYER, SYSTEM }

    /**
     * Who invoked this operation. {@code id} is the stable identifier for
     * this kind of principal (a remote IP for {@link Kind#WS_TOKEN}, a
     * player UUID for {@link Kind#PLAYER}, {@code "agent"} for
     * {@link Kind#SYSTEM}); {@code display} is a human-readable label (the
     * same IP, or a player name).
     */
    public record Principal(Kind kind, String id, String display) {
    }

    /** Receives {@code progress} events fired while a task runs. Never null on a context; see {@link #of}. */
    @FunctionalInterface
    public interface ProgressSink {
        void progress(long done, long total);
    }

    private static final ProgressSink NOOP = (done, total) -> { };

    private final Principal principal;
    private final String operationId;
    private final ProgressSink progress;
    private final Instant deadline;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    private InvocationContext(Principal principal, String operationId, ProgressSink progress, Instant deadline) {
        this.principal = principal;
        this.operationId = operationId;
        this.progress = progress != null ? progress : NOOP;
        this.deadline = deadline;
    }

    public Principal principal() {
        return principal;
    }

    /** The RPC request id as a string, or a generated {@code "op-<n>"} for internal (in-JVM) callers. */
    public String operationId() {
        return operationId;
    }

    /** Never null; a no-op sink by default. */
    public ProgressSink progress() {
        return progress;
    }

    /** Cooperative cancellation flag, checked by {@link net.rcwalter.ashlar.engine.TickBudgetExecutor}. */
    public boolean isCancelled() {
        return cancelled.get();
    }

    public void cancel() {
        cancelled.set(true);
    }

    public Optional<Instant> deadline() {
        return Optional.ofNullable(deadline);
    }

    public static InvocationContext of(Principal principal, String operationId, ProgressSink sink) {
        return new InvocationContext(principal, operationId, sink, null);
    }

    public static InvocationContext of(Principal principal, String operationId, ProgressSink sink, Instant deadline) {
        return new InvocationContext(principal, operationId, sink, deadline);
    }

    /** A {@link Kind#SYSTEM} context for an in-JVM caller with no transport session: NOOP sink, no deadline. */
    public static InvocationContext system(String operationId) {
        return new InvocationContext(new Principal(Kind.SYSTEM, "agent", "agent"), operationId, NOOP, null);
    }
}
