// SPDX-License-Identifier: AGPL-3.0-or-later
// Minimal Step 2 verification client. Not part of the plugin build; kept
// outside plugin/src per the Step 2 prompt's hard rules. Uses Node's global
// WebSocket (available without any npm install since Node 21+).
//
// Usage: node client.mjs <script.mjs-style-async-function-file-not-supported>
// In practice this file is imported by the individual check scripts below.

const HOST = process.env.MC_HOST || "127.0.0.1";
const PORT = process.env.MC_PORT || "8765";
const TOKEN = process.env.MC_TOKEN || "24e77d575101fd68043ba698c67bf45d";

export function connect() {
    return new Promise((resolve, reject) => {
        const ws = new WebSocket(`ws://${HOST}:${PORT}`);
        const events = [];
        ws.addEventListener("open", () => resolve({ ws, events }));
        ws.addEventListener("error", (e) => reject(e));
    });
}

let reqCounter = 0;

export function nextId() {
    reqCounter += 1;
    return `req-${reqCounter}`;
}

/** Sends a request and resolves with the matching response, collecting any progress events for that id along the way. */
export function call(ws, method, params, id = nextId()) {
    return new Promise((resolve, reject) => {
        const progress = [];
        const timeout = setTimeout(() => {
            ws.removeEventListener("message", onMessage);
            reject(new Error(`timeout waiting for response to ${method} (id=${id})`));
        }, 30000);

        function onMessage(ev) {
            const msg = JSON.parse(ev.data);
            if (msg.event === "progress" && msg.id === id) {
                progress.push(msg);
                return;
            }
            if (msg.id === id && (msg.ok !== undefined)) {
                clearTimeout(timeout);
                ws.removeEventListener("message", onMessage);
                resolve({ response: msg, progress });
            }
        }
        ws.addEventListener("message", onMessage);
        ws.send(JSON.stringify({ id, method, params }));
    });
}

export async function authenticate(ws) {
    const { response } = await call(ws, "auth", { token: TOKEN }, "auth-1");
    if (!response.ok) {
        throw new Error("auth failed: " + JSON.stringify(response));
    }
    return response;
}
