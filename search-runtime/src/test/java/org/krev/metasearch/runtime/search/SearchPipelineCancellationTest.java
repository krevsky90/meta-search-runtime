package org.krev.metasearch.runtime.search;

import org.junit.jupiter.api.Test;
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
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchPipelineCancellationTest {
    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(200);

    private static final String QUERY = "test";

    private static final SourceTimeouts SOURCE_TIMEOUTS =
            new SourceTimeouts(
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(5)
            );

    /**
     * Если вызывающий код отменяет публичный CompletableFuture,
     * уже отправленная CPU-задача должна получить cancel(true).
     */
    @Test
    void shouldCancelCpuTaskWhenCallerCancelsSearch() {
        SearchSourceClient sourceClient = successfulSourceClient();

        CapturingExecutor executor = new CapturingExecutor();

        SearchPipeline pipeline = new SearchPipeline(
                sourceClient,
                executor,
                Duration.ofSeconds(10),
                0,
                SOURCE_TIMEOUTS
        );

        CompletableFuture<SearchResultBox> result = pipeline.search(QUERY);

        Runnable submittedTask = executor.submittedTask();

        assertThat(submittedTask)
                .as("CPU task should have been submitted")
                .isNotNull();

        assertThat(submittedTask)
                .isInstanceOf(FutureTask.class);

        FutureTask<?> task = (FutureTask<?>) submittedTask;

        assertThat(task.isCancelled()).isFalse();

        // Cancel the future returned to the caller.
        assertThat(result.cancel(true)).isTrue();

        assertThat(result.isCancelled()).isTrue();

        // Cancellation must reach the actual CPU task.
        assertThat(task.isCancelled()).isTrue();
    }

    /**
     * Если истёк общий request deadline во время CPU processing,
     * CPU-задача должна быть отменена.
     */
    @Test
    void shouldCancelCpuTaskWhenRequestDeadlineExpires() {
        SearchSourceClient sourceClient = successfulSourceClient();

        CapturingExecutor executor = new CapturingExecutor();

        SearchPipeline pipeline = new SearchPipeline(
                sourceClient,
                executor,
                Duration.ofMillis(200),
                0,
                SOURCE_TIMEOUTS
        );

        CompletableFuture<SearchResultBox> result = pipeline.search(QUERY);

        Runnable submittedTask = executor.submittedTask();

        assertThat(submittedTask)
                .as("CPU task should have been submitted")
                .isNotNull();

        FutureTask<?> task = (FutureTask<?>) submittedTask;

        assertThat(task.isCancelled()).isFalse();

        // Wait for the global request deadline.
        assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(TimeoutException.class);

        assertThat(task.isCancelled()).isTrue();
    }

    /**
     * Если вызывающий код отменил запрос, пока ожидались внешние источники,
     * последующее завершение источников не должно запускать CPU processing.
     * <p>
     * CancellationContext уже отменён, поэтому register() должен
     * немедленно отменить новую задачу и вернуть false.
     */
    @Test
    void shouldNotSubmitCpuTaskWhenCallerCancelsBeforeSourcesComplete() {
        SourceCall<List<Product>> catalogFuture = new SourceCallTestImpl<>(new CompletableFuture<>());
        SourceCall<List<Offer>> offersFuture = new SourceCallTestImpl<>(new CompletableFuture<>());
        SourceCall<List<Review>> reviewsFuture = new SourceCallTestImpl<>(new CompletableFuture<>());

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

        CompletableFuture<SearchResultBox> result = pipeline.search(QUERY);

        // All sources are still pending.
        assertThat(executor.submittedTaskCount()).isZero();

        // Caller cancels the request.
        assertThat(result.cancel(true)).isTrue();

        // Sources complete after cancellation.
        catalogFuture.result().complete(List.of(new Product("product-1")));
        offersFuture.result().complete(List.of());
        reviewsFuture.result().complete(List.of());

        // CPU processing must not be submitted.
        assertThat(executor.submittedTaskCount()).isZero();
    }

    @Test
    void shouldCancelAllSourceCallsWhenCallerCancelsSearch() {
        SourceCallTestImpl<List<Product>> catalogFuture = new SourceCallTestImpl<>(new CompletableFuture<>());
        SourceCallTestImpl<List<Offer>> offersFuture = new SourceCallTestImpl<>(new CompletableFuture<>());
        SourceCallTestImpl<List<Review>> reviewsFuture = new SourceCallTestImpl<>(new CompletableFuture<>());

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

        CompletableFuture<SearchResultBox> result = pipeline.search(QUERY);

        // All sources are still pending.
        assertThat(executor.submittedTaskCount()).isZero();

        // Caller cancels the request.
        assertThat(result.cancel(true)).isTrue();

        // Sources complete after cancellation.
        assertThat(catalogFuture.result()).isCancelled();
        assertThat(offersFuture.result()).isCancelled();
        assertThat(reviewsFuture.result()).isCancelled();


        assertThat(catalogFuture.getCancelTransportCounter()).isEqualTo(1);
        assertThat(offersFuture.getCancelTransportCounter()).isEqualTo(1);
        assertThat(reviewsFuture.getCancelTransportCounter()).isEqualTo(1);
    }

    @Test
    void cancellingCompletedSearchHasNoEffect() {
        SearchSourceClient sourceClient = mock(SearchSourceClient.class);

        SourceCallTestImpl<List<Product>> catalogCall =
                new SourceCallTestImpl<>(
                        CompletableFuture.completedFuture(List.of(new Product("product")))
                );

        SourceCallTestImpl<List<Offer>> offersCall =
                new SourceCallTestImpl<>(
                        CompletableFuture.completedFuture(List.of(new Offer("offer")))
                );

        SourceCallTestImpl<List<Review>> reviewsCall =
                new SourceCallTestImpl<>(
                        CompletableFuture.completedFuture(List.of(new Review("review")))
                );

        when(sourceClient.searchCatalog(anyString())).thenReturn(catalogCall);
        when(sourceClient.searchOffers(anyString())).thenReturn(offersCall);
        when(sourceClient.searchReviews(anyString())).thenReturn(reviewsCall);

        try (TestSearchPipeline pipeline = new TestSearchPipeline(
                sourceClient,
                REQUEST_TIMEOUT,
                products -> products,
                products -> products
        )) {
            CompletableFuture<SearchResultBox> result = pipeline.search("query");

            SearchResultBox completedResult = result.join();

            assertThat(result.isDone()).isTrue();
            assertThat(result.isCompletedExceptionally()).isFalse();
            assertThat(result.isCancelled()).isFalse();

            // Отмена уже завершённого future должна вернуть false.
            assertThat(result.cancel(true)).isFalse();

            assertThat(result.isDone()).isTrue();
            assertThat(result.isCompletedExceptionally()).isFalse();
            assertThat(result.isCancelled()).isFalse();

            assertThat(result.join()).isSameAs(completedResult);

            // Внешние вызовы уже завершились — отменять транспорт не нужно.
            assertThat(catalogCall.getCancelTransportCounter()).isZero();
            assertThat(offersCall.getCancelTransportCounter()).isZero();
            assertThat(reviewsCall.getCancelTransportCounter()).isZero();
        }
    }

    private SearchSourceClient successfulSourceClient() {
        SearchSourceClient sourceClient = mock(SearchSourceClient.class);

        when(sourceClient.searchCatalog(QUERY))
                .thenReturn(new SourceCallTestImpl<>(CompletableFuture.completedFuture(
                        List.of(new Product("product-1"))
                )));

        when(sourceClient.searchOffers(QUERY))
                .thenReturn(new SourceCallTestImpl<>(CompletableFuture.completedFuture(
                        List.of()
                )));

        when(sourceClient.searchReviews(QUERY))
                .thenReturn(new SourceCallTestImpl<>(CompletableFuture.completedFuture(
                        List.of()
                )));

        return sourceClient;
    }

    private static class TestSearchPipeline extends SearchPipeline {
        private static final Duration SOURCE_TIMEOUT = Duration.ofSeconds(3);

        private final Function<List<Product>, List<Product>> filterFunction;
        private final Function<List<Product>, List<Product>> rankFunction;

        TestSearchPipeline(
                SearchSourceClient sourceClient,
                Duration requestTimeout,
                Function<List<Product>, List<Product>> filterFunction,
                Function<List<Product>, List<Product>> rankFunction
        ) {
            super(
                    sourceClient,
                    new ThreadPoolExecutor(
                            2,
                            2,
                            0L,
                            TimeUnit.MILLISECONDS,
                            new LinkedBlockingQueue<>()
                    ),
                    requestTimeout,
                    2L,
                    new SourceTimeouts(
                            SOURCE_TIMEOUT,
                            SOURCE_TIMEOUT,
                            SOURCE_TIMEOUT
                    )
            );

            this.filterFunction = filterFunction;
            this.rankFunction = rankFunction;
        }

        @Override
        List<Product> filterProducts(List<Product> products) {
            return filterFunction.apply(products);
        }

        @Override
        List<Product> rankProducts(List<Product> products) {
            return rankFunction.apply(products);
        }
    }
}