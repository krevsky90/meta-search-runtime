package org.krev.metasearch.runtime.error_model;

import org.krev.metasearch.runtime.model.SearchResult;

import java.util.List;
import java.util.Objects;

public record SearchResultBox(
        SearchResult result,
        List<SourceError> errors
) {
    public SearchResultBox {
        Objects.requireNonNull(result);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public boolean isSuccessful() {
        return errors.isEmpty();
    }
}
