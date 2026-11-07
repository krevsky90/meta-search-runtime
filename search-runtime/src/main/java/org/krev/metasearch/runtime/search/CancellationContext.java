
package org.krev.metasearch.runtime.search;

import org.krev.metasearch.runtime.external.SourceCall;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * Manages cancellation of operations belonging to a single search request.
 *
 * Each registered operation provides a cancellation action.
 * The action may cancel a CPU task, an external call, or another
 * asynchronous operation.
 */
public final class CancellationContext {
    public interface Registration {
        void unregister();
        boolean isActive();
    }

    private final List<RegisteredOperation> operations = new ArrayList<>();
    private boolean cancelled;

    public Registration register(Future<?> task, CompletableFuture<?> result) {
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(result, "result must not be null");

        return register(() -> {
            task.cancel(true);
            result.cancel(false);
        });
    }

    public Registration register(SourceCall<?> sourceCall) {
        Objects.requireNonNull(sourceCall, "sourceCall must not be null");
        return register(sourceCall::cancel);
    }

    public Registration register(Runnable cancelAction) {
        Objects.requireNonNull(cancelAction, "cancelAction must not be null");

        RegisteredOperation operation = new RegisteredOperation(cancelAction);

        synchronized (this) {
            if (!cancelled) {
                operations.add(operation);
                return operation;
            }
        }

        // Context was already cancelled.
        operation.cancel();
        return operation;
    }

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
            try {
                operation.cancel();
            } catch (RuntimeException e) {
                // TODO: log cancellation failure
            }
        }
    }

    public synchronized boolean isCancelled() {
        return cancelled;
    }

    private final class RegisteredOperation implements Registration {
        private final Runnable cancelAction;
        // NOTE: unregister() и cancel() конкурируют за одну регистрацию, но операция отменяется не более одного раза, т.к. есть
        //      if (unregistered) {
        //                    return;
        //                }
        private boolean unregistered;

        private RegisteredOperation(Runnable cancelAction) {
            this.cancelAction = cancelAction;
        }

        @Override
        public void unregister() {
            synchronized (CancellationContext.this) {
                if (unregistered) {
                    return;
                }

                unregistered = true;
                operations.remove(this);
            }
        }

        @Override
        public boolean isActive() {
            synchronized (CancellationContext.this) {
                return !unregistered && !cancelled;
            }
        }

        private void cancel() {
            synchronized (CancellationContext.this) {
                if (unregistered) {
                    return;
                }

                unregistered = true;
                operations.remove(this);
            }

            cancelAction.run();
        }
    }
}
