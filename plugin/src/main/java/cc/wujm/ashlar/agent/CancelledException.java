// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.agent;

/**
 * Thrown by {@link ModelClient#chat} when the caller's cancellation flag flips true while a
 * request is in flight or waiting between retry attempts (mirrors the abrupt rejection {@code
 * provider.ts} gets from an aborted {@code AbortSignal}).
 */
public final class CancelledException extends RuntimeException {

    public CancelledException() {
        super("cancelled");
    }
}
