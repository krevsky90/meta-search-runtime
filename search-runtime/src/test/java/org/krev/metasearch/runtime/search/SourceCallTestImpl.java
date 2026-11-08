package org.krev.metasearch.runtime.search;

import org.krev.metasearch.runtime.external.SourceCall;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public class SourceCallTestImpl<T> implements SourceCall<T> {
    private final CompletableFuture<T> result;
    private final AtomicInteger cancelTransportCounter = new AtomicInteger(0);
    private final Runnable onCancelTransport;

    SourceCallTestImpl(CompletableFuture<T> result) {
        this(result, () -> {});
    }

    SourceCallTestImpl(CompletableFuture<T> result, Runnable onCancelTransport) {
        this.result = Objects.requireNonNull(result);
        this.onCancelTransport = Objects.requireNonNull(onCancelTransport);
    }

    @Override
    public CompletableFuture<T> result() {
        return result;
    }

    @Override
    public void cancelTransport() {
        cancelTransportCounter.incrementAndGet();
        onCancelTransport.run();
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