// SPDX-License-Identifier: AGPL-3.0-or-later

import assert from "node:assert/strict";
import { writeFileSync, unlinkSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

import { ConfigError } from "../config.js";
import { loadAgentConfig } from "./config.js";

const AGENT_ENV_VARS = [
    "AI_BASE_URL",
    "AI_API_KEY",
    "AI_MODEL",
    "AI_MAX_TOOL_CALLS",
    "AI_MAX_REQUESTS_PER_PLAYER_PER_DAY",
    "AI_ALLOW_COMMAND",
    "AI_MAX_CONCURRENT",
    "AI_HISTORY_TURNS",
    "AI_HISTORY_TTL_MINUTES",
    "AI_IMAGE_DETAIL",
    "AI_SYSTEM_PROMPT_FILE",
    "AI_REQUEST_TIMEOUT_MS",
    "AI_USAGE_FILE",
    "AI_PRICE_INPUT",
    "AI_PRICE_CACHED_INPUT",
    "AI_PRICE_OUTPUT",
    "AI_CURRENCY",
    "AI_MAX_TOKENS_PER_PLAYER_PER_DAY",
    "AI_MAX_COST_PER_PLAYER_PER_DAY",
    "AI_PEAK_HOURS",
    "AI_OFF_PEAK_MULTIPLIER"
];

/** Runs `body` with only `overrides` set among the AI_* env vars, restoring the previous environment afterwards. */
function withAgentEnv(overrides: Record<string, string | undefined>, body: () => void): void {
    const saved: Record<string, string | undefined> = {};
    for (const name of AGENT_ENV_VARS) saved[name] = process.env[name];
    try {
        for (const name of AGENT_ENV_VARS) delete process.env[name];
        for (const [name, value] of Object.entries(overrides)) {
            if (value !== undefined) process.env[name] = value;
        }
        body();
    } finally {
        for (const name of AGENT_ENV_VARS) {
            if (saved[name] === undefined) delete process.env[name];
            else process.env[name] = saved[name];
        }
    }
}

test("loadAgentConfig: defaults", () => {
    withAgentEnv({ AI_API_KEY: "sk-test" }, () => {
        const cfg = loadAgentConfig();
        assert.equal(cfg.baseUrl, "https://api.deepseek.com");
        assert.equal(cfg.apiKey, "sk-test");
        assert.equal(cfg.model, "deepseek-flash");
        assert.equal(cfg.maxToolCalls, 25);
        assert.equal(cfg.maxRequestsPerPlayerPerDay, 40);
        assert.equal(cfg.allowCommand, false);
        assert.equal(cfg.maxConcurrent, 2);
        assert.equal(cfg.historyTurns, 6);
        assert.equal(cfg.historyTtlMinutes, 30);
        assert.equal(cfg.imageDetail, "high");
        assert.equal(cfg.systemPromptExtra, undefined);
        assert.equal(cfg.requestTimeoutMs, 120_000);
        assert.equal(cfg.usageFile, "./ashlar-usage.json");
        assert.equal(cfg.priceInput, 0.3);
        assert.equal(cfg.priceCachedInput, 0.006);
        assert.equal(cfg.priceOutput, 1.2);
        assert.equal(cfg.currency, "USD");
        assert.equal(cfg.maxTokensPerPlayerPerDay, 0);
        assert.equal(cfg.maxCostPerPlayerPerDay, 0);
        assert.equal(cfg.offPeakMultiplier, 0.5);
        assert.equal(cfg.peakHours.always, false);
        assert.equal(cfg.peakHours.days, "mon-fri");
        assert.deepEqual(cfg.peakHours.windows, [
            { startMin: 60, endMin: 240 },
            { startMin: 360, endMin: 600 }
        ]);
    });
});

test("loadAgentConfig: AI_USAGE_FILE, price and limit overrides", () => {
    withAgentEnv(
        {
            AI_API_KEY: "sk-test",
            AI_USAGE_FILE: "/tmp/custom-usage.json",
            AI_PRICE_INPUT: "1.5",
            AI_PRICE_CACHED_INPUT: "0",
            AI_PRICE_OUTPUT: "3",
            AI_CURRENCY: "CNY",
            AI_MAX_TOKENS_PER_PLAYER_PER_DAY: "500000",
            AI_MAX_COST_PER_PLAYER_PER_DAY: "1.5",
            AI_OFF_PEAK_MULTIPLIER: "0.25"
        },
        () => {
            const cfg = loadAgentConfig();
            assert.equal(cfg.usageFile, "/tmp/custom-usage.json");
            assert.equal(cfg.priceInput, 1.5);
            assert.equal(cfg.priceCachedInput, 0);
            assert.equal(cfg.priceOutput, 3);
            assert.equal(cfg.currency, "CNY");
            assert.equal(cfg.maxTokensPerPlayerPerDay, 500_000);
            assert.equal(cfg.maxCostPerPlayerPerDay, 1.5);
            assert.equal(cfg.offPeakMultiplier, 0.25);
        }
    );
});

test("loadAgentConfig: rejects a negative AI_PRICE_INPUT", () => {
    withAgentEnv({ AI_API_KEY: "sk-test", AI_PRICE_INPUT: "-1" }, () => {
        assert.throws(() => loadAgentConfig(), (err: unknown) => {
            assert.ok(err instanceof ConfigError);
            assert.match(err.message, /AI_PRICE_INPUT/);
            return true;
        });
    });
});

test("loadAgentConfig: AI_PEAK_HOURS accepts 'always'", () => {
    withAgentEnv({ AI_API_KEY: "sk-test", AI_PEAK_HOURS: "always" }, () => {
        const cfg = loadAgentConfig();
        assert.equal(cfg.peakHours.always, true);
    });
});

test("loadAgentConfig: rejects a malformed AI_PEAK_HOURS", () => {
    withAgentEnv({ AI_API_KEY: "sk-test", AI_PEAK_HOURS: "mon-fri 1:00-4:00" }, () => {
        assert.throws(() => loadAgentConfig(), (err: unknown) => {
            assert.ok(err instanceof ConfigError);
            assert.match(err.message, /AI_PEAK_HOURS/);
            return true;
        });
    });
});

test("loadAgentConfig: AI_API_KEY is required", () => {
    withAgentEnv({}, () => {
        assert.throws(() => loadAgentConfig(), (err: unknown) => {
            assert.ok(err instanceof ConfigError);
            assert.match(err.message, /AI_API_KEY/);
            return true;
        });
    });
});

test("loadAgentConfig: a trailing slash on AI_BASE_URL is stripped", () => {
    withAgentEnv({ AI_API_KEY: "sk-test", AI_BASE_URL: "https://example.com/v1/" }, () => {
        const cfg = loadAgentConfig();
        assert.equal(cfg.baseUrl, "https://example.com/v1");
    });
});

test("loadAgentConfig: rejects a non-numeric AI_MAX_TOOL_CALLS", () => {
    withAgentEnv({ AI_API_KEY: "sk-test", AI_MAX_TOOL_CALLS: "not-a-number" }, () => {
        assert.throws(() => loadAgentConfig(), (err: unknown) => {
            assert.ok(err instanceof ConfigError);
            assert.match(err.message, /AI_MAX_TOOL_CALLS/);
            return true;
        });
    });
});

test("loadAgentConfig: rejects a zero/negative AI_MAX_CONCURRENT (must be positive)", () => {
    withAgentEnv({ AI_API_KEY: "sk-test", AI_MAX_CONCURRENT: "0" }, () => {
        assert.throws(() => loadAgentConfig(), (err: unknown) => {
            assert.ok(err instanceof ConfigError);
            assert.match(err.message, /AI_MAX_CONCURRENT/);
            return true;
        });
    });
});

test("loadAgentConfig: AI_MAX_REQUESTS_PER_PLAYER_PER_DAY allows 0 (unlimited)", () => {
    withAgentEnv({ AI_API_KEY: "sk-test", AI_MAX_REQUESTS_PER_PLAYER_PER_DAY: "0" }, () => {
        const cfg = loadAgentConfig();
        assert.equal(cfg.maxRequestsPerPlayerPerDay, 0);
    });
});

test("loadAgentConfig: rejects a negative AI_MAX_REQUESTS_PER_PLAYER_PER_DAY", () => {
    withAgentEnv({ AI_API_KEY: "sk-test", AI_MAX_REQUESTS_PER_PLAYER_PER_DAY: "-1" }, () => {
        assert.throws(() => loadAgentConfig(), (err: unknown) => {
            assert.ok(err instanceof ConfigError);
            assert.match(err.message, /AI_MAX_REQUESTS_PER_PLAYER_PER_DAY/);
            return true;
        });
    });
});

test("loadAgentConfig: AI_ALLOW_COMMAND accepts 0/1, rejects anything else", () => {
    withAgentEnv({ AI_API_KEY: "sk-test", AI_ALLOW_COMMAND: "1" }, () => {
        assert.equal(loadAgentConfig().allowCommand, true);
    });
    withAgentEnv({ AI_API_KEY: "sk-test", AI_ALLOW_COMMAND: "yes" }, () => {
        assert.throws(() => loadAgentConfig(), ConfigError);
    });
});

test("loadAgentConfig: rejects an invalid AI_IMAGE_DETAIL", () => {
    withAgentEnv({ AI_API_KEY: "sk-test", AI_IMAGE_DETAIL: "ultra" }, () => {
        assert.throws(() => loadAgentConfig(), (err: unknown) => {
            assert.ok(err instanceof ConfigError);
            assert.match(err.message, /AI_IMAGE_DETAIL/);
            return true;
        });
    });
});

test("loadAgentConfig: reads AI_SYSTEM_PROMPT_FILE when set", () => {
    const path = join(tmpdir(), `ashlar-agent-prompt-${process.pid}.txt`);
    writeFileSync(path, "Extra server rules.");
    try {
        withAgentEnv({ AI_API_KEY: "sk-test", AI_SYSTEM_PROMPT_FILE: path }, () => {
            const cfg = loadAgentConfig();
            assert.equal(cfg.systemPromptExtra, "Extra server rules.");
        });
    } finally {
        unlinkSync(path);
    }
});

test("loadAgentConfig: a missing AI_SYSTEM_PROMPT_FILE is a ConfigError", () => {
    withAgentEnv({ AI_API_KEY: "sk-test", AI_SYSTEM_PROMPT_FILE: "/nonexistent/path/prompt.txt" }, () => {
        assert.throws(() => loadAgentConfig(), (err: unknown) => {
            assert.ok(err instanceof ConfigError);
            assert.match(err.message, /AI_SYSTEM_PROMPT_FILE/);
            return true;
        });
    });
});
