
package org.krev.metasearch.runtime.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * Manages cancellation of operations belonging to a single search request.
 * <p>
 * Each registered operation consists of:
 * - Future: handle used to interrupt the actual executing task;
 * - CompletableFuture: handle used to complete the asynchronous result.
 *
 * CancellationContext хранит оба объекта:
 * Future<?> task - нужен для попытки остановить выполнение
 * CompletableFuture<?> result - чтобы сообщить остальному pipeline, что результат больше не будет получен.
 */
public final class CancellationContext {
    private final List<RegisteredOperation> operations = new ArrayList<>();
    private boolean cancelled;

    /**
     * Registers an operation.
     *
     * @return true if the operation was registered;
     * false if the context was already cancelled.
     */
    public boolean register(Future<?> task, CompletableFuture<?> result) {
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(result, "result must not be null");

        synchronized (this) {
            if (!cancelled) {
                operations.add(new RegisteredOperation(task, result));
                return true;
            }
        }

        // The context was already cancelled.
        cancelOperation(new RegisteredOperation(task, result));
        return false;
    }

    /**
     * Removes an operation after its execution has finished.
     */
    public synchronized void unregister(Future<?> task, CompletableFuture<?> result) {
        operations.removeIf(operation -> operation.task() == task && operation.result() == result);
    }

    /**
     * Cancels all registered operations.
     * This method is idempotent.
     */
    public void cancel() {
        List<RegisteredOperation> operationsToCancel;

        synchronized (this) {
            if (cancelled) {
                return;
            }

            cancelled = true;

            operationsToCancel = List.copyOf(operations);
            operations.clear();
        }

        // Do not hold the lock while invoking cancellation.
        for (RegisteredOperation operation : operationsToCancel) {
            cancelOperation(operation);
        }
    }

    public synchronized boolean isCancelled() {
        return cancelled;
    }

    private void cancelOperation(RegisteredOperation operation) {
        operation.task().cancel(true);
        operation.result().cancel(false);
    }

    private record RegisteredOperation(Future<?> task, CompletableFuture<?> result) {
    }
}
