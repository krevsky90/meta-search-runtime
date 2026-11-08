package org.krev.metasearch.runtime.external;

import java.util.concurrent.CompletableFuture;

public interface SourceCall<T> {
    CompletableFuture<T> result();
    // Отменить физический внешний вызов.
    void cancelTransport();

    // Отменить вызов как операцию, включая result.
    void cancel();
}