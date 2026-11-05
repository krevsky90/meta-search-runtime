package org.krev.metasearch.runtime.error_model;

import org.krev.metasearch.runtime.model.SearchResult;

import java.util.List;
import java.util.Objects;

public record SearchResultBox(
        SearchResult result,
        List<SourceError> errors,
        List<ProcessingError> processingErrors
) {
    public SearchResultBox {
        Objects.requireNonNull(result);

        errors = errors == null ? List.of() : List.copyOf(errors);
        processingErrors = processingErrors == null ? List.of() : List.copyOf(processingErrors);
    }

    public boolean isSuccessful() {
        return errors.isEmpty() &&  processingErrors.isEmpty();
    }
}
