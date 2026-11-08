package org.krev.metasearch.runtime.search;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * проверяем, что после unregister() операция больше не удерживается контекстом и последующий cancel() её не затрагивает.
 * То есть после завершения CPU-задачи она должна удаляться из контекста.
 */
class CancellationContextCleanupTest {
    @Test
    void shouldNotCancelUnregisteredOperation() {
        CancellationContext context = new CancellationContext();

        CountingFuture<Void> task = new CountingFuture<>(() -> null);
        CompletableFuture<String> result = new CompletableFuture<>();

        CancellationContext.Registration registration = context.register(task, result);

        assertThat(registration.isActive()).isTrue();

        registration.unregister();
        context.cancel();

        assertThat(task.getCancelCount()).isZero();
        assertThat(task.isCancelled()).isFalse();
        assertThat(result.isCancelled()).isFalse();
    }
}