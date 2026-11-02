package org.krev.metasearch.runtime.error_model;

import java.util.Objects;

public record SearchBox<T>(
        T data,
        SourceError error
) {
    public SearchBox {
        Objects.requireNonNull(data);
    }

    public boolean isSuccessful() {
        return error == null;
    }
}