// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.rpc;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Unit tests for {@link InvocationContext} (plan.md step7): principal,
 * operation id, progress sink, cooperative cancellation and deadline.
 * Pure Java, no Bukkit/paper-api dependency.
 */
class InvocationContextTest {

    @Test
    void ofCarriesPrincipalOperationIdAndSink() {
        InvocationContext.Principal principal =
                new InvocationContext.Principal(InvocationContext.Kind.WS_TOKEN, "127.0.0.1", "127.0.0.1");
        AtomicInteger calls = new AtomicInteger();
        InvocationContext.ProgressSink sink = (done, total) -> calls.incrementAndGet();

        InvocationContext ctx = InvocationContext.of(principal, "req-1", sink);

        assertEquals(principal, ctx.principal());
        assertEquals("req-1", ctx.operationId());
        ctx.progress().progress(5, 10);
        assertEquals(1, calls.get());
    }

    @Test
    void ofWithoutDeadlineHasEmptyDeadline() {
        InvocationContext ctx = InvocationContext.of(
                new InvocationContext.Principal(InvocationContext.Kind.PLAYER, "uuid-1", "Steve"), "req-2", null);

        assertEquals(Optional.empty(), ctx.deadline());
    }

    @Test
    void ofWithDeadlineCarriesIt() {
        Instant deadline = Instant.now().plusSeconds(30);

        InvocationContext ctx = InvocationContext.of(
                new InvocationContext.Principal(InvocationContext.Kind.PLAYER, "uuid-1", "Steve"), "req-3", null, deadline);

        assertEquals(Optional.of(deadline), ctx.deadline());
    }

    @Test
    void nullSinkDefaultsToNoop() {
        InvocationContext ctx = InvocationContext.of(
                new InvocationContext.Principal(InvocationContext.Kind.WS_TOKEN, "127.0.0.1", "127.0.0.1"), "req-4", null);

        assertDoesNotThrow(() -> ctx.progress().progress(1, 2));
    }

    @Test
    void cancelSetsIsCancelled() {
        InvocationContext ctx = InvocationContext.of(
                new InvocationContext.Principal(InvocationContext.Kind.WS_TOKEN, "127.0.0.1", "127.0.0.1"), "req-5", null);

        assertFalse(ctx.isCancelled());
        ctx.cancel();
        assertTrue(ctx.isCancelled());
    }

    @Test
    void systemContextHasSystemPrincipalAndNoopSink() {
        InvocationContext ctx = InvocationContext.system("op-1");

        assertEquals(InvocationContext.Kind.SYSTEM, ctx.principal().kind());
        assertEquals("agent", ctx.principal().id());
        assertEquals("op-1", ctx.operationId());
        assertEquals(Optional.empty(), ctx.deadline());
        assertFalse(ctx.isCancelled());
        assertDoesNotThrow(() -> ctx.progress().progress(0, 100));
    }
}
