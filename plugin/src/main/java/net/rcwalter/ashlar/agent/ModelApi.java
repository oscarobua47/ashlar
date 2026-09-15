// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.agent;

import net.rcwalter.ashlar.agent.model.ChatMessage;
import net.rcwalter.ashlar.agent.model.ToolDef;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The model-calling seam {@link AgentRunner} depends on; {@link ModelClient} is the real
 * implementation, tests supply a fake. Throws {@link ModelException} for a non-2xx response and
 * {@link CancelledException} when {@code cancelled} flips true; any transport-level failure is
 * reported as an unchecked exception (typically {@link java.io.UncheckedIOException}), never a
 * checked one, so callers do not need a {@code throws} clause.
 */
public interface ModelApi {

    Reply chat(List<ChatMessage> messages, List<ToolDef> tools, ToolChoice toolChoice, BooleanSupplier cancelled);
}
