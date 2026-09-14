// SPDX-License-Identifier: AGPL-3.0-or-later

import { Client, InMemoryTransport } from "@modelcontextprotocol/client";

import type { PluginClient } from "../plugin-client.js";
import { buildServer } from "../server.js";
import type { ToolDef } from "./provider.js";

export interface ToolCallResult {
    text: string;
    images: Array<{ data: string; mimeType: string }>;
    isError: boolean;
}

export interface ToolBridge {
    /** OpenAI `tools[].function` shape, descriptions passed through verbatim from the mc_* tool registration. */
    tools: ToolDef[];
    callTool(name: string, args: unknown): Promise<ToolCallResult>;
    close(): void;
}

/**
 * Builds an in-process bridge from the OpenAI tool-calling shape to the
 * existing mc_* MCP tools, with zero duplication of tool code
 * (docs/prompts/step6b-prompt.md): an `InMemoryTransport` linked pair
 * connects a fresh `McpServer` (from {@link buildServer}) to an MCP
 * `Client`, and `listTools()`/`callTool()` do the rest.
 */
export async function createToolBridge(pluginClient: PluginClient, opts: { allowCommand: boolean }): Promise<ToolBridge> {
    const server = buildServer(pluginClient);
    const [serverTransport, clientTransport] = InMemoryTransport.createLinkedPair();
    const client = new Client({ name: "ashlar-agent", version: "0.1.0" });

    await Promise.all([server.connect(serverTransport), client.connect(clientTransport)]);

    const { tools: mcpTools } = await client.listTools();
    const filtered = mcpTools.filter(t => opts.allowCommand || t.name !== "mc_command");
    const validNames = new Set(filtered.map(t => t.name));

    const tools: ToolDef[] = filtered.map(t => ({
        type: "function",
        function: {
            name: t.name,
            description: t.description ?? "",
            parameters: t.inputSchema
        }
    }));

    async function callTool(name: string, args: unknown): Promise<ToolCallResult> {
        if (!validNames.has(name)) {
            return {
                text: `Unknown tool "${name}". Valid tools: ${[...validNames].join(", ")}.`,
                images: [],
                isError: true
            };
        }

        const result = await client.callTool({ name, arguments: args as Record<string, unknown> });
        const texts: string[] = [];
        const images: Array<{ data: string; mimeType: string }> = [];
        const content = (result.content ?? []) as Array<{ type: string; text?: string; data?: string; mimeType?: string }>;
        for (const block of content) {
            if (block.type === "text" && typeof block.text === "string") {
                texts.push(block.text);
            } else if (block.type === "image" && typeof block.data === "string" && typeof block.mimeType === "string") {
                images.push({ data: block.data, mimeType: block.mimeType });
            }
        }

        return { text: texts.join("\n"), images, isError: result.isError === true };
    }

    return {
        tools,
        callTool,
        close: () => {
            void client.close();
            void server.close();
        }
    };
}
