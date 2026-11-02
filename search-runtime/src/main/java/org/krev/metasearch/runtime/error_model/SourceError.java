package org.krev.metasearch.runtime.error_model;

public record SourceError(
        String source,
        ErrorCode code,
        String message
) {
}