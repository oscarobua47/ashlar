// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.rpc;

/**
 * Stable machine-readable error codes returned in RPC error responses.
 * All codes are defined up front, even ones not used until later steps,
 * so later RPC methods can rely on them without touching this enum again.
 */
public enum ErrorCode {
    UNAUTHORIZED,
    BAD_REQUEST,
    UNKNOWN_METHOD,
    VOLUME_EXCEEDED,
    WORLD_NOT_ALLOWED,
    OUT_OF_BUILD_REGION,
    QUEUE_FULL,
    INVALID_BLOCK,
    DISABLED,
    CANCELLED,
    INTERNAL
}
