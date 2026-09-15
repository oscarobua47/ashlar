// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.agent;

/**
 * Mirrors the {@code tool_choice} field {@code provider.ts} sends: {@link #AUTO} means the field
 * is omitted from the request body entirely (the model's own default), {@link #NONE} sends the
 * literal string {@code "none"} (used once the per-request tool-call budget is exhausted).
 */
public enum ToolChoice {
    AUTO,
    NONE
}
