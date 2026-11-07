package org.krev.metasearch.runtime.search;

import org.junit.jupiter.api.Test;
import org.krev.metasearch.runtime.error_model.SearchResultBox;
import org.krev.metasearch.runtime.error_model.SourceTimeouts;
import org.krev.metasearch.runtime.external.SearchSourceClient;
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
        CompletableFuture<List<Product>> catalogFuture = new CompletableFuture<>();
        CompletableFuture<List<Offer>> offersFuture = new CompletableFuture<>();
        CompletableFuture<List<Review>> reviewsFuture = new CompletableFuture<>();

        SearchSourceClient sourceClient = mock(SearchSourceClient.class);

        when(sourceClient.searchCatalog(QUERY)).thenReturn(catalogFuture);
        when(sourceClient.searchOffers(QUERY)).thenReturn(offersFuture);
        when(sourceClient.searchReviews(QUERY)).thenReturn(reviewsFuture);

        CapturingExecutor executor = new CapturingExecutor();

        SearchPipeline pipeline = new SearchPipeline(
                sourceClient,
                executor,
                Duration.ofSeconds(10),
                0,
                SOURCE_TIMEOUTS
        );

        ExecutorService racers = Executors.newFixedThreadPool(2);

        try {
            CompletableFuture<SearchResultBox> result = pipeline.search(QUERY);

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);

            Future<?> sourceCompletion = racers.submit(() -> {
                ready.countDown();
                await(start);

                catalogFuture.complete(List.of(new Product("product-1")));
                offersFuture.complete(List.of());
                reviewsFuture.complete(List.of());
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
}