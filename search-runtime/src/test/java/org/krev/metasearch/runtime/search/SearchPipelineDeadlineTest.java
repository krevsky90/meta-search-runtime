
package org.krev.metasearch.runtime.search;

import org.krev.metasearch.runtime.error_model.SearchResultBox;
import org.krev.metasearch.runtime.error_model.SourceTimeouts;
import org.krev.metasearch.runtime.external.SearchSourceClient;
import org.krev.metasearch.runtime.external.SourceCall;
import org.krev.metasearch.runtime.model.Offer;
import org.krev.metasearch.runtime.model.Product;
import org.krev.metasearch.runtime.model.Review;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SearchPipelineDeadlineTest {

    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(200);
    private static final Duration SOURCE_TIMEOUT = Duration.ofSeconds(3);

    private static final Product PRODUCT = new Product("product-1");
    private static final Offer OFFER = new Offer("offer-1");
    private static final Review REVIEW = new Review("review-1");

    @Test
    void shouldReturnResultWhenAllStagesCompleteBeforeDeadline() {
        SearchSourceClient sourceClient = successfulSourceClient();

        try (TestSearchPipeline pipeline = new TestSearchPipeline(
                sourceClient,
                REQUEST_TIMEOUT,
                products -> products,
                products -> products
        )) {
            SearchResultBox result = pipeline.search("query").join();

            assertThat(result.result().products()).containsExactly(PRODUCT);
            assertThat(result.result().offers()).containsExactly(OFFER);
            assertThat(result.result().reviews()).containsExactly(REVIEW);
            assertThat(result.isSuccessful()).isTrue();
        }
    }

    @Test
    void shouldTimeoutWhenFilteringExceedsDeadline() throws Exception {
        SearchSourceClient sourceClient = successfulSourceClient();

        CountDownLatch filterStarted = new CountDownLatch(1);
        CountDownLatch releaseFilter = new CountDownLatch(1);

        try (TestSearchPipeline pipeline = new TestSearchPipeline(
                sourceClient,
                REQUEST_TIMEOUT,
                products -> {
                    filterStarted.countDown();
                    await(releaseFilter);
                    return products;
                },
                products -> products
        )) {
            CompletableFuture<SearchResultBox> result =
                    pipeline.search("query");

            assertThat(filterStarted.await(1, TimeUnit.SECONDS)).isTrue();

            try {
                assertTimeout(result);
            } finally {
                releaseFilter.countDown();
            }
        }
    }

    @Test
    void shouldTimeoutWhenRankingExceedsDeadline() throws Exception {
        SearchSourceClient sourceClient = successfulSourceClient();

        CountDownLatch rankingStarted = new CountDownLatch(1);
        CountDownLatch releaseRanking = new CountDownLatch(1);

        try (TestSearchPipeline pipeline = new TestSearchPipeline(
                sourceClient,
                REQUEST_TIMEOUT,
                products -> products,
                products -> {
                    rankingStarted.countDown();
                    await(releaseRanking);
                    return products;
                }
        )) {
            CompletableFuture<SearchResultBox> result =
                    pipeline.search("query");

            assertThat(rankingStarted.await(1, TimeUnit.SECONDS)).isTrue();

            try {
                assertTimeout(result);
            } finally {
                releaseRanking.countDown();
            }
        }
    }

    @Test
    void shouldTimeoutWhenSourcesExceedRequestDeadline() {
        SearchSourceClient sourceClient = mock(SearchSourceClient.class);

        when(sourceClient.searchCatalog(anyString()))
                .thenReturn(new SourceCallTestImpl<>(new CompletableFuture<>()));
        when(sourceClient.searchOffers(anyString()))
                .thenReturn(new SourceCallTestImpl<>(new CompletableFuture<>()));
        when(sourceClient.searchReviews(anyString()))
                .thenReturn(new SourceCallTestImpl<>(new CompletableFuture<>()));

        try (TestSearchPipeline pipeline = new TestSearchPipeline(
                sourceClient,
                REQUEST_TIMEOUT,
                products -> products,
                products -> products
        )) {
            CompletableFuture<SearchResultBox> result =
                    pipeline.search("query");

            assertTimeout(result);
        }
    }

    @Test
    void shouldUseOneDeadlineForFilteringAndRankingTogether() {
        SearchSourceClient sourceClient = successfulSourceClient();

        Duration stageDuration = Duration.ofMillis(130);

        try (TestSearchPipeline pipeline = new TestSearchPipeline(
                sourceClient,
                Duration.ofMillis(200),
                products -> {
                    sleep(stageDuration);
                    return products;
                },
                products -> {
                    sleep(stageDuration);
                    return products;
                }
        )) {
            CompletableFuture<SearchResultBox> result =
                    pipeline.search("query");

            assertTimeout(result);
        }
    }

    private static void assertTimeout(
            CompletableFuture<SearchResultBox> future
    ) {
        assertThatThrownBy(future::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(TimeoutException.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ex);
        }
    }

    private static void sleep(Duration duration) {
        try {
            TimeUnit.NANOSECONDS.sleep(duration.toNanos());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ex);
        }
    }

    private static SearchSourceClient successfulSourceClient() {
        SearchSourceClient sourceClient = mock(SearchSourceClient.class);

        when(sourceClient.searchCatalog(anyString()))
                .thenReturn(new SourceCallTestImpl<>(CompletableFuture.completedFuture(List.of(PRODUCT))));
        when(sourceClient.searchOffers(anyString()))
                .thenReturn(new SourceCallTestImpl<>(CompletableFuture.completedFuture(List.of(OFFER))));
        when(sourceClient.searchReviews(anyString()))
                .thenReturn(new SourceCallTestImpl<>(CompletableFuture.completedFuture(List.of(REVIEW))));

        return sourceClient;
    }

    private static class TestSearchPipeline extends SearchPipeline {

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

        private final Function<List<Product>, List<Product>> filterFunction;
        private final Function<List<Product>, List<Product>> rankFunction;

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