package org.krev.metasearch.runtime.error_model;

import java.time.Duration;
import java.util.Objects;

public record SourceTimeouts(
        Duration catalog,
        Duration offers,
        Duration reviews
) {
    public SourceTimeouts {
        validate(catalog, "catalog");
        validate(offers, "offers");
        validate(reviews, "reviews");
    }

    private static void validate(Duration timeout, String source) {
        Objects.requireNonNull(timeout, source + " timeout");

        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(source + " timeout must be positive");
        }
    }
}