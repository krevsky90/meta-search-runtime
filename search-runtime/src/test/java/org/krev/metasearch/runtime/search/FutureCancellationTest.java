package org.krev.metasearch.runtime.search;

import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

class FutureCancellationTest {
    // cancel(true) отправляет interrupt worker-потоку.
    // releaseWorker.await() выбрасывает InterruptedException.
    // Мы фиксируем это через workerInterrupted.
    @Test
    void submit_cancelTrue_shouldInterruptWorker() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>()
        );

        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);

        try {
            Future<?> future = executor.submit(() -> {
                workerStarted.countDown();

                try {
                    releaseWorker.await();
                } catch (InterruptedException e) {
                    workerInterrupted.countDown();
                    Thread.currentThread().interrupt();
                }
            });

            assertThat(workerStarted.await(2, TimeUnit.SECONDS)).isTrue();

            boolean cancelled = future.cancel(true);

            assertThat(cancelled).isTrue();
            assertThat(workerInterrupted.await(2, TimeUnit.SECONDS)).isTrue();

            assertThat(future.isDone()).isTrue();
            assertThat(future.isCancelled()).isTrue();

        } finally {
            releaseWorker.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    // cancel(true) отменяет CompletableFuture.
    // Worker не получает interrupt.
    // Worker продолжает ждать на releaseWorker.
    // После освобождения latch задача завершается, хотя CompletableFuture остаётся отменённым.
    // Обрати внимание: во втором тесте 200 ms — это ограниченное окно наблюдения, а не доказательство отсутствия interrupt в абсолютном смысле.
    //      Для нашего контролируемого эксперимента этого достаточно: задача гарантированно уже запущена,
    //      а CompletableFuture.cancel(true) не отправляет ей interrupt.
    @Test
    void supplyAsync_cancelTrue_shouldNotInterruptWorker() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>()
        );

        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch workerCompleted = new CountDownLatch(1);

        try {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                workerStarted.countDown();

                try {
                    releaseWorker.await();
                } catch (InterruptedException e) {
                    workerInterrupted.countDown();
                    Thread.currentThread().interrupt();
                } finally {
                    workerCompleted.countDown();
                }
            }, executor);

            assertThat(workerStarted.await(2, TimeUnit.SECONDS)).isTrue();

            boolean cancelled = future.cancel(true);

            assertThat(cancelled).isTrue();

            // Даём worker возможность отреагировать на interrupt,
            // если бы он был отправлен.
            assertThat(workerInterrupted.await(200, TimeUnit.MILLISECONDS)).isFalse();

            assertThat(future.isDone()).isTrue();
            assertThat(future.isCancelled()).isTrue();

            // Сам worker всё ещё выполняется.
            assertThat(workerCompleted.getCount()).isEqualTo(1);

            // Разрешаем worker продолжить выполнение.
            releaseWorker.countDown();

            assertThat(workerCompleted.await(2, TimeUnit.SECONDS)).isTrue();

            // CompletableFuture остаётся отменённым,
            // хотя фактическая задача успешно завершила работу.
            assertThat(future.isCancelled()).isTrue();

        } finally {
            releaseWorker.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }
}
