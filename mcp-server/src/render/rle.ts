// SPDX-License-Identifier: AGPL-3.0-or-later

/**
 * Decodes the plugin's palette + run-length-encoded region format (spec
 * section 3.4) into a randomly-accessible index array. Pure, no plugin/MCP
 * dependency, so it is unit-testable in isolation.
 */

/** One {@code read_region} sign entry (Fix 3, step4d): a sign block's text/appearance, read from its block entity. */
export interface SignEntry {
    pos: [number, number, number];
    block: string;
    front: string[];
    back: string[];
    waxed: boolean;
}

export interface RegionDataJson {
    bounds: { from: [number, number, number]; to: [number, number, number] };
    order: string;
    palette: string[];
    runs: Array<[number, number]>;
    volume: number;
    /** Sign block-entity text/appearance for every sign in the region (Fix 3, step4d), capped server-side. */
    signs?: SignEntry[];
    signsTruncated?: boolean;
}

export interface DecodedRegion {
    bounds: { from: [number, number, number]; to: [number, number, number] };
    /** Cell counts along each axis. */
    size: { dx: number; dy: number; dz: number };
    palette: string[];
    /** Flat palette-index array in y-outer/z-middle/x-inner order, matching the plugin's encoding order. */
    indices: Uint16Array;
    volume: number;
    /** Block state string at an absolute world coordinate. Throws if outside the decoded bounds. */
    at(x: number, y: number, z: number): string;
    /** Iterates every cell's block state string in encoding order (y outer, z middle, x inner). */
    [Symbol.iterator](): IterableIterator<string>;
}

/**
 * Expands `runs` (each `[paletteIndex, runLength]`) into a flat index array
 * and wraps it with bounds-checked random access. Throws if the runs do not
 * add up to exactly the region's volume (`(dx)*(dy)*(dz)`), which would mean
 * the encoding is corrupt or the bounds are wrong.
 */
export function decodeRegionData(data: RegionDataJson): DecodedRegion {
    const [minX, minY, minZ] = data.bounds.from;
    const [maxX, maxY, maxZ] = data.bounds.to;
    const dx = maxX - minX + 1;
    const dy = maxY - minY + 1;
    const dz = maxZ - minZ + 1;
    const total = dx * dy * dz;

    if (dx <= 0 || dy <= 0 || dz <= 0) {
        throw new Error(`rle: invalid bounds ${JSON.stringify(data.bounds)}`);
    }

    const indices = new Uint16Array(total);
    let pos = 0;
    for (const [idx, len] of data.runs) {
        if (len < 1) {
            throw new Error(`rle: run length must be >= 1, got ${len}`);
        }
        if (pos + len > total) {
            throw new Error(`rle: runs overflow region volume ${total} (at position ${pos}, run length ${len})`);
        }
        indices.fill(idx, pos, pos + len);
        pos += len;
    }
    if (pos !== total) {
        throw new Error(`rle: decoded ${pos} cells but region volume is ${total}`);
    }

    const palette = data.palette;

    function flatIndex(x: number, y: number, z: number): number {
        const xi = x - minX;
        const yi = y - minY;
        const zi = z - minZ;
        if (xi < 0 || xi >= dx || yi < 0 || yi >= dy || zi < 0 || zi >= dz) {
            throw new Error(`rle: (${x},${y},${z}) is outside the decoded region ${JSON.stringify(data.bounds)}`);
        }
        return (yi * dz + zi) * dx + xi;
    }

    return {
        bounds: data.bounds,
        size: { dx, dy, dz },
        palette,
        indices,
        volume: total,
        at(x, y, z) {
            const p = palette[indices[flatIndex(x, y, z)]!];
            if (p === undefined) {
                throw new Error(`rle: palette index out of range at (${x},${y},${z})`);
            }
            return p;
        },
        [Symbol.iterator](): IterableIterator<string> {
            let i = 0;
            return {
                [Symbol.iterator]() {
                    return this;
                },
                next(): IteratorResult<string> {
                    if (i >= indices.length) {
                        return { done: true, value: undefined as unknown as string };
                    }
                    const value = palette[indices[i]!]!;
                    i++;
                    return { done: false, value };
                }
            };
        }
    };
}
