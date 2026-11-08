package org.krev.metasearch.runtime.search;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;

import static org.assertj.core.api.Assertions.assertThat;

class CancellationContextIdempotencyTest {

    @Test
    void shouldCancelOperationOnlyOnceWhenContextCancelledRepeatedly() {
        CancellationContext context = new CancellationContext();

        CountingFuture<String> task = new CountingFuture<>(() -> "do smth");
        CompletableFuture<String> result = new CompletableFuture<>();

        assertThat(context.register(task, result).isActive()).isTrue();

        context.cancel();
        context.cancel();
        context.cancel();

        assertThat(context.isCancelled()).isTrue();
        assertThat(task.getCancelCount()).isEqualTo(1);
        assertThat(task.isCancelled()).isTrue();
        assertThat(result.isCancelled()).isTrue();
    }

    @Test
    void shouldNotChangeAlreadyCompletedOperationWhenContextCancelled() {
        CancellationContext context = new CancellationContext();

        FutureTask<Void> task = new FutureTask<>(() -> null);
        task.run();

        CompletableFuture<String> result = CompletableFuture.completedFuture("completed");

        assertThat(task.isDone()).isTrue();
        assertThat(task.isCancelled()).isFalse();

        assertThat(context.register(task, result).isActive()).isTrue();

        context.cancel();

        assertThat(task.isDone()).isTrue();
        assertThat(task.isCancelled()).isFalse();

        assertThat(result.isDone()).isTrue();
        assertThat(result.isCancelled()).isFalse();
        assertThat(result.join()).isEqualTo("completed");
    }
}