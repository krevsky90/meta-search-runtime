package org.krev.metasearch.runtime.search;

import org.junit.jupiter.api.Test;
import org.krev.metasearch.runtime.config.SearchRuntimeConfig;
import org.krev.metasearch.runtime.error_model.SearchResultBox;
import org.krev.metasearch.runtime.error_model.SourceTimeouts;
import org.krev.metasearch.runtime.external.SearchSourceClient;
import org.krev.metasearch.runtime.external.SourceCall;
import org.krev.metasearch.runtime.model.Offer;
import org.krev.metasearch.runtime.model.Product;
import org.krev.metasearch.runtime.model.Review;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SearchPipelineCancellationRaceTest {
    private static final String QUERY = "test";
    private static final SourceTimeouts SOURCE_TIMEOUTS =
            new SourceTimeouts(
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(10)
            );

    @Test
    void shouldCancelCpuTaskWhenSourcesCompleteConcurrentlyWithRequestCancellation() throws Exception {
        SourceCall<List<Product>> catalogFuture = new SourceCallTestImpl<>(new CompletableFuture<>());
        SourceCall<List<Offer>> offersFuture = new SourceCallTestImpl<>(new CompletableFuture<>());
        SourceCall<List<Review>> reviewsFuture = new SourceCallTestImpl<>(new CompletableFuture<>());

        SearchSourceClient sourceClient = mock(SearchSourceClient.class);

        when(sourceClient.searchCatalog(QUERY)).thenReturn(catalogFuture);
        when(sourceClient.searchOffers(QUERY)).thenReturn(offersFuture);
        when(sourceClient.searchReviews(QUERY)).thenReturn(reviewsFuture);

        CapturingExecutor executor = new CapturingExecutor();

        SearchRuntimeConfig config = new SearchRuntimeConfig(
                Duration.ofSeconds(10),
                0,
                SOURCE_TIMEOUTS
        );

        SearchPipeline pipeline = new SearchPipeline(
                sourceClient,
                executor,
                config
        );

        ExecutorService racers = Executors.newFixedThreadPool(2);

        try {
            CompletableFuture<SearchResultBox> result = pipeline.search(QUERY);

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);

            Future<?> sourceCompletion = racers.submit(() -> {
                ready.countDown();
                await(start);

                catalogFuture.result().complete(List.of(new Product("product-1")));
                offersFuture.result().complete(List.of());
                reviewsFuture.result().complete(List.of());
            });

            Future<?> requestCancellation = racers.submit(() -> {
                ready.countDown();
                await(start);

                result.cancel(true);
            });

            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();

            // Both threads start concurrently.
            start.countDown();

            sourceCompletion.get(2, TimeUnit.SECONDS);
            requestCancellation.get(2, TimeUnit.SECONDS);

            assertThat(result.isCancelled()).isTrue();

            Runnable submittedTask = executor.submittedTask();

            // Either cancellation prevented registration,
            // or the registered task was cancelled.
            if (submittedTask != null) {
                assertThat(submittedTask).isInstanceOf(FutureTask.class);
                assertThat(((FutureTask<?>) submittedTask).isCancelled()).isTrue();
            }
        } finally {
            racers.shutdownNow();
            executor.shutdownNow();
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

    static class CapturingExecutor extends ThreadPoolExecutor {
        private final BlockingQueue<Runnable> submittedTasks = new LinkedBlockingQueue<>();

        CapturingExecutor() {
            super(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>()
            );
        }

        @Override
        public void execute(Runnable command) {
            submittedTasks.add(command);
        }

        Runnable submittedTask() {
            return submittedTasks.peek();
        }
    }

    /**
     * здесь важно не требовать конкретного победителя гонки. Возможны два корректных исхода:
     * - FutureTask успела завершиться, и результат успел попасть в CompletableFuture.
     * - Отмена успела первой, и CompletableFuture перешёл в cancelled state.
     *
     * NOTE:
     * FutureTask может уже завершиться успешно, но CancellationContext ещё успеет отменить связанный с ней CompletableFuture,
     *      прежде чем callback done() передаст туда результат.
     * Поэтому нельзя проверять только соответствие task.isCancelled() и result.isCancelled().
     *
     * NOTE:
     * однократный запуск не гарантирует, что планировщик действительно воспроизведёт оба порядка выполнения.
     * Он проверяет корректность при конкурентном старте, но не является доказательством всех возможных interleaving.
     * Для MVP это полезный regression test; при желании позже можно добавить многократный stress test.
     */
    @Test
    void shouldHandleRaceBetweenTaskCompletionAndCancellation() throws Exception {
        CancellationContext context = new CancellationContext();

        CompletableFuture<Integer> result = new CompletableFuture<>();

        FutureTask<Integer> task = new FutureTask<>(() -> 42) {
            @Override
            protected void done() {
                try {
                    result.complete(get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    result.completeExceptionally(e);
                } catch (ExecutionException e) {
                    result.completeExceptionally(e.getCause());
                } catch (CancellationException e) {
                    result.cancel(false);
                }
            }
        };

        assertThat(context.register(task, result).isActive()).isTrue();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService raceExecutor = Executors.newFixedThreadPool(2);

        try {
            Future<?> completion = raceExecutor.submit(() -> {
                ready.countDown();
                start.await();

                task.run();

                return null;
            });

            Future<?> cancellation = raceExecutor.submit(() -> {
                ready.countDown();
                start.await();

                context.cancel();

                return null;
            });

            // Both threads are ready to race.
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();

            // Allow both operations to start.
            start.countDown();

            completion.get(2, TimeUnit.SECONDS);
            cancellation.get(2, TimeUnit.SECONDS);

            assertThat(context.isCancelled()).isTrue();

            // Both objects must reach a terminal state.
            assertThat(task.isDone()).isTrue();
            assertThat(result.isDone()).isTrue();

            // Either the result wins or cancellation wins.
            if (result.isCancelled()) {
                assertThat(result.isCompletedExceptionally()).isTrue();
            } else {
                assertThat(result.join()).isEqualTo(42);
            }
        } finally {
            start.countDown();

            raceExecutor.shutdownNow();
            assertThat(raceExecutor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }
}