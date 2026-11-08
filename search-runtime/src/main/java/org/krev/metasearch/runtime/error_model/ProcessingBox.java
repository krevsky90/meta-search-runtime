package org.krev.metasearch.runtime.error_model;

import java.util.Objects;

public record ProcessingBox<T>(
        T data,
        ProcessingError error
) {
    public ProcessingBox {
        Objects.requireNonNull(data);
    }

    public boolean isSuccessful() {
        return error == null;
    }
}
