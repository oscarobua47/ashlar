// SPDX-License-Identifier: AGPL-3.0-or-later

import type { AgentConfig } from "./config.js";
import type { History } from "./history.js";
import { buildSystemPrompt } from "./prompt.js";
import { chatCompletion, type ChatMessage, type ContentPart } from "./provider.js";
import type { ToolBridge } from "./tools.js";

/** The subset of the plugin's `chat` event's `player` object the runner needs. */
export interface PlayerInfo {
    name: string;
    uuid: string;
    world: string;
    pos: [number, number, number];
    facing: string;
    inFront: [number, number, number];
    gameMode: string;
}

export interface RunRequestOptions {
    cfg: AgentConfig;
    bridge: ToolBridge;
    history: History;
    player: PlayerInfo;
    text: string;
    signal: AbortSignal;
    onProgress: (line: string) => void;
    /** Test seam: defaults to the real {@link chatCompletion}; runner.test.ts injects a scripted fake here. */
    chatFn?: typeof chatCompletion;
}

const SHORT_ARG_KEYS = ["from", "to", "view", "action"] as const;

/** Builds the "> <tool> <short args>" progress line's argument portion from the parsed tool-call arguments. */
function shortArgsOf(args: unknown): string {
    if (!args || typeof args !== "object") return "";
    const obj = args as Record<string, unknown>;
    const parts: string[] = [];
    for (const key of SHORT_ARG_KEYS) {
        if (obj[key] !== undefined) {
            parts.push(`${key}=${JSON.stringify(obj[key])}`);
        }
    }
    return parts.join(" ");
}

function textOfContent(content: ChatMessage["content"]): string {
    if (typeof content === "string") return content;
    if (Array.isArray(content)) {
        return content
            .filter((p): p is Extract<ContentPart, { type: "text" }> => p.type === "text")
            .map(p => p.text)
            .join("\n");
    }
    return "";
}

/**
 * Drives one player request through the model + mc_* tools loop
 * (docs/prompts/step6b-prompt.md): builds the message list (system prompt,
 * remembered history, then the user's text with a `[context]` suffix),
 * repeatedly calls the provider, executes any tool calls it returns via
 * `bridge.callTool`, and stops either on a plain-text reply, on the tool
 * budget being exhausted, or on `signal` being aborted between steps.
 * Records the exchange (the plain-text user message onward) into `history`
 * unless the request was cancelled.
 */
export async function runRequest(opts: RunRequestOptions): Promise<string> {
    const { cfg, bridge, history, player, text, signal, onProgress, chatFn = chatCompletion } = opts;

    const contextLine =
        `${text}\n\n[context] player ${player.name} in world ${player.world} at pos ${player.pos.join(",")} ` +
        `(ground at y=${player.pos[1] - 1}) facing ${player.facing}, block in front ${player.inFront.join(",")}, ` +
        `gamemode ${player.gameMode}`;

    const rawUserMessage: ChatMessage = { role: "user", content: text };
    const contextUserMessage: ChatMessage = { role: "user", content: contextLine };

    const messages: ChatMessage[] = [
        { role: "system", content: buildSystemPrompt(cfg.systemPromptExtra) },
        ...history.get(player.uuid),
        contextUserMessage
    ];

    // What gets recorded to history: the plain-text user message plus everything sent/received after it.
    const exchange: ChatMessage[] = [rawUserMessage];

    let toolCallCount = 0;
    let budgetExhausted = false;

    for (;;) {
        if (signal.aborted) {
            return "Cancelled.";
        }

        if (budgetExhausted) {
            const note: ChatMessage = { role: "user", content: "[system] Tool budget exhausted - summarise what was done and stop." };
            messages.push(note);
            exchange.push(note);
        }

        const { message } = await chatFn(cfg, {
            // A snapshot, not the live array: chatFn implementations (and tests) must not observe
            // messages appended after this call, since `messages` keeps growing for the rest of the loop.
            messages: [...messages],
            tools: bridge.tools,
            toolChoice: budgetExhausted ? "none" : undefined,
            signal
        });
        messages.push(message);
        exchange.push(message);

        const toolCalls = message.tool_calls ?? [];
        if (toolCalls.length === 0 || budgetExhausted) {
            const replyText = textOfContent(message.content).trim();
            const finalText = replyText.length > 0 ? replyText : "(no reply)";
            history.append(player.uuid, exchange);
            return finalText;
        }

        const imagesForThisTurn: Array<{ toolName: string; toolCallId: string; images: Array<{ data: string; mimeType: string }> }> = [];

        for (const call of toolCalls) {
            if (signal.aborted) {
                return "Cancelled.";
            }

            let args: unknown = {};
            let parseError: string | null = null;
            const rawArgs = call.function.arguments;
            try {
                args = rawArgs && rawArgs.trim().length > 0 ? JSON.parse(rawArgs) : {};
            } catch (err) {
                parseError = `invalid tool call arguments JSON: ${(err as Error).message}`;
            }

            const shortArgs = shortArgsOf(args);
            onProgress(shortArgs ? `> ${call.function.name} ${shortArgs}` : `> ${call.function.name}`);

            const result = parseError
                ? { text: parseError, images: [] as Array<{ data: string; mimeType: string }>, isError: true }
                : await bridge.callTool(call.function.name, args);

            toolCallCount++;

            const toolMessage: ChatMessage = {
                role: "tool",
                tool_call_id: call.id,
                content: result.text || "(no text)"
            };
            messages.push(toolMessage);
            exchange.push(toolMessage);

            if (result.images.length > 0) {
                imagesForThisTurn.push({ toolName: call.function.name, toolCallId: call.id, images: result.images });
            }
        }

        for (const entry of imagesForThisTurn) {
            const imageMessage: ChatMessage = {
                role: "user",
                content: [
                    { type: "text", text: `Image(s) returned by ${entry.toolName} (tool call ${entry.toolCallId}):` },
                    ...entry.images.map(img => ({
                        type: "image_url" as const,
                        image_url: { url: `data:${img.mimeType};base64,${img.data}`, detail: cfg.imageDetail }
                    }))
                ]
            };
            messages.push(imageMessage);
            exchange.push(imageMessage);
        }

        if (toolCallCount >= cfg.maxToolCalls) {
            budgetExhausted = true;
        }
    }
}
