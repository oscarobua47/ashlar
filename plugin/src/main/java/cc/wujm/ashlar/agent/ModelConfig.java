// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.agent;

import java.time.Duration;

/** Connection settings for {@link ModelClient}. {@code baseUrl} has no trailing slash. */
public record ModelConfig(String baseUrl, String apiKey, String model, Duration requestTimeout) {
}
