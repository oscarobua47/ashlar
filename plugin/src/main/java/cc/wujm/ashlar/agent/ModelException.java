// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.agent;

/**
 * Thrown for a non-2xx response from the model API that isn't retried away (mirrors {@code
 * mcp-server/src/agent/provider.ts}'s {@code ProviderError}). {@code status} is the HTTP status
 * code, or 0 when there was no response at all (retries exhausted, or a malformed response body).
 */
public final class ModelException extends RuntimeException {

    private final int status;

    public ModelException(int status, String message) {
        super("model API returned " + status + ": " + message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
