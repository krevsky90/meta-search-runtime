package org.krev.metasearch.runtime.error_model;

import java.util.Objects;

public record ProcessingError(ProcessingStage processingStage, ProcessingErrorCode code, String message) {
    public ProcessingError {
        Objects.requireNonNull(processingStage);
        Objects.requireNonNull(code);
        Objects.requireNonNull(message);
    }
}
