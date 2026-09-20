// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Built-in system prompt for the in-game AI building assistant (pure-Java port of {@code
 * mcp-server/src/agent/prompt.ts}). The base text lives in {@code /agent/system-prompt.txt},
 * verbatim from the TypeScript source's template literal.
 */
public final class SystemPrompt {

    private static final String BASE_PROMPT = loadBasePrompt();

    private SystemPrompt() {
    }

    private static String loadBasePrompt() {
        try (InputStream in = SystemPrompt.class.getResourceAsStream("/agent/system-prompt.txt")) {
            if (in == null) {
                throw new IllegalStateException("missing resource /agent/system-prompt.txt");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).stripTrailing();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Returns the built-in system prompt, with {@code extra} appended when given and non-blank. */
    public static String build(Optional<String> extra) {
        String trimmedExtra = extra.map(String::trim).filter(s -> !s.isEmpty()).orElse(null);
        if (trimmedExtra != null) {
            return BASE_PROMPT + "\n\n" + trimmedExtra;
        }
        return BASE_PROMPT;
    }
}
