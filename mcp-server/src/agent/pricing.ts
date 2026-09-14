// SPDX-License-Identifier: AGPL-3.0-or-later

/**
 * Peak/off-peak pricing schedule (docs/private/prompts/step6f-prompt.md):
 * the configured `AI_PRICE_*` values are peak prices; outside the schedule's
 * windows, `AI_OFF_PEAK_MULTIPLIER` applies. `AI_PEAK_HOURS=always` disables
 * the discount entirely (every hour is billed at the peak rate) - useful for
 * providers that do not offer one.
 */

export type PeakDayRange = "daily" | "mon-fri" | "sat-sun";

export interface PeakWindow {
    /** Minutes since UTC midnight, 0-1439. */
    startMin: number;
    /** Minutes since UTC midnight, 0-1439. `endMin <= startMin` means the window wraps past midnight. */
    endMin: number;
}

export interface PeakSchedule {
    /** true = `AI_PEAK_HOURS=always`: every hour is peak, `windows` is empty and ignored. */
    always: boolean;
    days: PeakDayRange;
    windows: PeakWindow[];
}

const WINDOW_RE = /^(\d{2}):(\d{2})-(\d{2}):(\d{2})$/;

function parseWindow(token: string, raw: string): PeakWindow {
    const m = WINDOW_RE.exec(token);
    if (!m) {
        throw new Error(`window must be HH:MM-HH:MM (got "${token}" in "${raw}")`);
    }
    const [, h1, mi1, h2, mi2] = m as unknown as [string, string, string, string, string];
    const startMin = Number(h1) * 60 + Number(mi1);
    const endMin = Number(h2) * 60 + Number(mi2);
    if (startMin > 1439 || endMin > 1439) {
        throw new Error(`window hours/minutes out of range (got "${token}" in "${raw}")`);
    }
    return { startMin, endMin };
}

/**
 * Parses `AI_PEAK_HOURS`: `always`, or an optional day range (`mon-fri`,
 * `sat-sun`, `daily`; default `daily`) followed by comma-separated
 * `HH:MM-HH:MM` UTC windows (a window may wrap midnight, e.g. `22:00-02:00`).
 * Throws a plain {@link Error} on malformed input; callers wrap it in a
 * {@link ConfigError} naming the environment variable.
 */
export function parsePeakHours(raw: string): PeakSchedule {
    const trimmed = raw.trim();
    if (trimmed.toLowerCase() === "always") {
        return { always: true, days: "daily", windows: [] };
    }

    const firstSpace = trimmed.indexOf(" ");
    let days: PeakDayRange = "daily";
    let rest = trimmed;
    if (firstSpace > 0) {
        const candidate = trimmed.slice(0, firstSpace).toLowerCase();
        if (candidate === "mon-fri" || candidate === "sat-sun" || candidate === "daily") {
            days = candidate;
            rest = trimmed.slice(firstSpace + 1).trim();
        }
    }

    if (!rest) {
        throw new Error(`no time windows given (got "${raw}")`);
    }

    const windows = rest.split(",").map(token => parseWindow(token.trim(), raw));
    return { always: false, days, windows };
}

/** Whether `date` (evaluated in UTC) falls within `schedule`'s day range and time windows. */
export function isPeak(schedule: PeakSchedule, date: Date): boolean {
    if (schedule.always) return true;

    const utcDay = date.getUTCDay(); // 0 = Sunday ... 6 = Saturday
    const isWeekend = utcDay === 0 || utcDay === 6;
    if (schedule.days === "mon-fri" && isWeekend) return false;
    if (schedule.days === "sat-sun" && !isWeekend) return false;

    const minuteOfDay = date.getUTCHours() * 60 + date.getUTCMinutes();
    for (const w of schedule.windows) {
        if (w.startMin <= w.endMin) {
            if (minuteOfDay >= w.startMin && minuteOfDay < w.endMin) return true;
        } else {
            // Wraps midnight, e.g. 22:00-02:00.
            if (minuteOfDay >= w.startMin || minuteOfDay < w.endMin) return true;
        }
    }
    return false;
}

/** The price multiplier in effect at `date`: 1 during peak hours, `offPeakMultiplier` otherwise. */
export function priceMultiplier(schedule: PeakSchedule, offPeakMultiplier: number, date: Date): number {
    return isPeak(schedule, date) ? 1 : offPeakMultiplier;
}
