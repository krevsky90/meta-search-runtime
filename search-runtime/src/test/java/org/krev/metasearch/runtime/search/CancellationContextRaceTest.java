package org.krev.metasearch.runtime.search;

import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

class CancellationContextRaceTest {
    /**
     * проверяем непосредственно CancellationContext.
     * <p>
     * Два потока одновременно вызывают:
     * - register(task, result);
     * - cancel().
     * Независимо от того, какой поток выиграет гонку, задача должна быть отменена.
     */
    @Test
    void shouldCancelOperationWhenRegistrationRacesWithContextCancellation() throws Exception {
        CancellationContext context = new CancellationContext();

        FutureTask<Void> task = new FutureTask<>(() -> null);
        CompletableFuture<String> result = new CompletableFuture<>();

        ExecutorService racers = Executors.newFixedThreadPool(2);

        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);

            AtomicBoolean registered = new AtomicBoolean();

            Future<?> registration = racers.submit(() -> {
                ready.countDown();
                await(start);

                registered.set(context.register(task, result).isActive());
            });

            Future<?> cancellation = racers.submit(() -> {
                ready.countDown();
                await(start);

                context.cancel();
            });

            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();

            start.countDown();

            registration.get(2, TimeUnit.SECONDS);
            cancellation.get(2, TimeUnit.SECONDS);

            assertThat(context.isCancelled()).isTrue();
            assertThat(task.isCancelled()).isTrue();
            assertThat(result.isCancelled()).isTrue();

            // If registration won the race, cancel() handled it.
            // Otherwise register() cancelled it immediately.
            // Both outcomes are valid.
        } finally {
            racers.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}