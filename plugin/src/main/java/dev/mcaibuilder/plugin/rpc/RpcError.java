// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.mcaibuilder.plugin.rpc;

/**
 * Exception thrown by RPC handlers to signal a well-defined, client-facing
 * error. {@link RpcDispatcher} translates it into an RPC error response
 * carrying {@link #code()} and the exception message. Any other exception
 * type is treated as an internal error.
 */
public final class RpcError extends RuntimeException {

    private final ErrorCode code;

    public RpcError(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
