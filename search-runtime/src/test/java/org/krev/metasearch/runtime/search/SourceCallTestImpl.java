package org.krev.metasearch.runtime.search;

import org.krev.metasearch.runtime.external.SourceCall;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public class SourceCallTestImpl<T> implements SourceCall<T> {
    private final CompletableFuture<T> result;
    private final AtomicInteger cancelTransportCounter = new AtomicInteger(0);

    SourceCallTestImpl(CompletableFuture<T> result) {
        this.result = Objects.requireNonNull(result);
    }

    @Override
    public CompletableFuture<T> result() {
        return result;
    }

    @Override
    public void cancelTransport() {
        cancelTransportCounter.incrementAndGet();
    }

    @Override
    public void cancel() {
        try {
            cancelTransport();
        } finally {
            result.cancel(false);
        }
    }

    public int getCancelTransportCounter() {
        return cancelTransportCounter.get();
    }
}