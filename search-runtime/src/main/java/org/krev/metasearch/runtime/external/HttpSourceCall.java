package org.krev.metasearch.runtime.external;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Just as example
 */
public final class HttpSourceCall<T> implements SourceCall<T> {
    private final CompletableFuture<T> result;
    // NOTE:
    // Здесь cancelAction должен обращаться к реальному механизму отмены HTTP-клиента, а не просто менять состояние future.
    // Это пока концептуальный пример. Реализация cancelAction зависит от конкретной библиотеки.

    //todo: продумать обработку исключений из cancelAction.
    private final Runnable cancelAction;

    // решает проблему:
    //      cancelTransport могут одновременно вызвать два сценария:
    //      - локальный таймаут конкретного источника;
    //      - глобальная отмена поискового запроса через CancellationContext.
    private final AtomicBoolean transportCancelled = new AtomicBoolean();

    public HttpSourceCall(CompletableFuture<T> result, Runnable cancelAction) {
        this.result = Objects.requireNonNull(result);
        this.cancelAction = Objects.requireNonNull(cancelAction);
    }

    @Override
    public CompletableFuture<T> result() {
        return result;
    }

    @Override
    public void cancelTransport() {
        if (transportCancelled.compareAndSet(false, true)) {
            cancelAction.run();
        }
    }

    @Override
    public void cancel() {
        try {
            cancelTransport();
        } finally {
            // even if cancelTransport fails, result will be cancelled anyway
            result.cancel(false);
        }
    }
}