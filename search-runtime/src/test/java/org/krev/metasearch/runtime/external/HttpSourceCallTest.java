package org.krev.metasearch.runtime.external;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class HttpSourceCallTest {
    @Test
    void concurrentCancelTransportAndCancelShouldInvokeCancelActionOnlyOnce() throws Exception {
        AtomicInteger cancelActionCalls = new AtomicInteger();
        CompletableFuture<String> result = new CompletableFuture<>();

        HttpSourceCall<String> call = new HttpSourceCall<>(
                result,
                cancelActionCalls::incrementAndGet
        );

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            executor.submit(() -> {
                ready.countDown();
                await(start);
                // имитируем локальный timeout
                call.cancelTransport();
            });

            executor.submit(() -> {
                ready.countDown();
                await(start);
                // имитируем глобальную отмену через CancellationContext.
                call.cancel();
            });

            assertThat(ready.await(1, TimeUnit.SECONDS)).isTrue();

            // Both threads start cancellation simultaneously.
            start.countDown();

            executor.shutdown();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }

        // cancelAction должен выполниться ровно один раз.
        // NOTE: мы не проверяем, какой поток победил. Это неважно.
        //      Важно, что физическая отмена транспорта инициируется не более одного раза.
        assertThat(cancelActionCalls).hasValue(1);

        assertThat(result).isCancelled();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}