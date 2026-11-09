package org.krev.metasearch.runtime.config;

import org.krev.metasearch.runtime.error_model.SourceTimeouts;

import java.time.Duration;
import java.util.Objects;

public record SearchRuntimeConfig(Duration requestTimeout,
                                  long terminationTimeoutSeconds,
                                  SourceTimeouts sourceTimeouts) {
    public SearchRuntimeConfig {
        Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
        Objects.requireNonNull(sourceTimeouts, "sourceTimeouts must not be null");

        if (requestTimeout.compareTo(Duration.ZERO) <= 0) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }

        if (terminationTimeoutSeconds < 0) {
            throw new IllegalArgumentException("terminationTimeoutSeconds must not be negative");
        }
    }
}
