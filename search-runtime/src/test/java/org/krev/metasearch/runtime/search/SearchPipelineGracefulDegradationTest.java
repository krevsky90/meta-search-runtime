package org.krev.metasearch.runtime.search;

import org.junit.jupiter.api.Test;
import org.krev.metasearch.runtime.error_model.*;
import org.krev.metasearch.runtime.exceptions.AllSourcesUnavailableException;
import org.krev.metasearch.runtime.external.SearchSourceClient;
import org.krev.metasearch.runtime.model.Offer;
import org.krev.metasearch.runtime.model.Product;
import org.krev.metasearch.runtime.model.Review;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchPipelineGracefulDegradationTest {

    private static final String QUERY = "phone";

    private static final Duration SOURCE_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private static final Product PRODUCT_1 = new Product("product-1");
    private static final Product PRODUCT_2 = new Product("product-2");

    private static final Offer OFFER_1 = new Offer("offer-1");
    private static final Review REVIEW_1 = new Review("review-1");

    /**
     * Catalog недоступен, но Offers и Reviews доступны.
     *
     * Ожидаем:
     * - поиск возвращает частичный результат;
     * - products пустой;
     * - offers и reviews сохранены;
     * - SourceError содержит ошибку catalog;
     * - CPU processing не запускается.
     */
    @Test
    void shouldReturnPartialResultWhenCatalogIsUnavailable() {
        SearchSourceClient sourceClient = mock(SearchSourceClient.class);

        when(sourceClient.searchCatalog(anyString()))
                .thenReturn(CompletableFuture.failedFuture(
                        new IOException("Catalog unavailable")
                ));

        when(sourceClient.searchOffers(anyString()))
                .thenReturn(CompletableFuture.completedFuture(List.of(OFFER_1)));

        when(sourceClient.searchReviews(anyString()))
                .thenReturn(CompletableFuture.completedFuture(List.of(REVIEW_1)));

        AtomicInteger filterCalls = new AtomicInteger();
        AtomicInteger rankCalls = new AtomicInteger();

        try (TestSearchPipeline pipeline = new TestSearchPipeline(
                sourceClient,
                Function.identity(),
                Function.identity(),
                filterCalls,
                rankCalls
        )) {
            SearchResultBox result = pipeline.search(QUERY).join();

            assertThat(result.result().products()).isEmpty();
            assertThat(result.result().offers()).containsExactly(OFFER_1);
            assertThat(result.result().reviews()).containsExactly(REVIEW_1);

            assertThat(result.errors()).hasSize(1);
            assertThat(result.errors().get(0).source()).isEqualTo("catalog");
            assertThat(result.errors().get(0).code())
                    .isEqualTo(ErrorCode.CONNECTION_FAILED);

            assertThat(result.processingErrors()).isEmpty();
            assertThat(result.isSuccessful()).isFalse();

            assertThat(filterCalls).hasValue(0);
            assertThat(rankCalls).hasValue(0);
        }
    }

    /**
     * Фильтрация завершается исключением.
     *
     * Ожидаем:
     * - products заменяются на пустой список;
     * - создаётся ProcessingError для FILTER_PRODUCTS;
     * - ранжирование не запускается;
     * - offers и reviews сохраняются.
     */
    @Test
    void shouldReturnPartialResultWhenFilteringFails() {
        SearchSourceClient sourceClient = successfulSourceClient();

        AtomicInteger filterCalls = new AtomicInteger();
        AtomicInteger rankCalls = new AtomicInteger();

        Function<List<Product>, List<Product>> filterFunction = products -> {
            filterCalls.incrementAndGet();
            throw new IllegalStateException("Filtering failed");
        };

        try (TestSearchPipeline pipeline = new TestSearchPipeline(
                sourceClient,
                filterFunction,
                Function.identity(),
                filterCalls,
                rankCalls
        )) {
            SearchResultBox result = pipeline.search(QUERY).join();

            assertThat(result.result().products()).isEmpty();
            assertThat(result.result().offers()).containsExactly(OFFER_1);
            assertThat(result.result().reviews()).containsExactly(REVIEW_1);

            assertThat(result.errors()).isEmpty();

            assertThat(result.processingErrors()).hasSize(1);
            assertThat(result.processingErrors().get(0).processingStage())
                    .isEqualTo(ProcessingStage.FILTER_PRODUCTS);
            assertThat(result.processingErrors().get(0).code())
                    .isEqualTo(ProcessingErrorCode.INTERNAL_ERROR);

            assertThat(result.isSuccessful()).isFalse();

            assertThat(filterCalls).hasValue(1);
            assertThat(rankCalls).hasValue(0);
        }
    }

    /**
     * Ранжирование завершается исключением.
     *
     * Ожидаем:
     * - сохраняется результат фильтрации;
     * - создаётся ProcessingError для RANK_PRODUCTS;
     * - offers и reviews сохраняются.
     */
    @Test
    void shouldReturnFilteredProductsWhenRankingFails() {
        SearchSourceClient sourceClient = successfulSourceClient();

        AtomicInteger filterCalls = new AtomicInteger();
        AtomicInteger rankCalls = new AtomicInteger();

        Function<List<Product>, List<Product>> filterFunction = products -> {
            filterCalls.incrementAndGet();
            return List.of(PRODUCT_2);
        };

        Function<List<Product>, List<Product>> rankFunction = products -> {
            rankCalls.incrementAndGet();
            throw new IllegalStateException("Ranking failed");
        };

        try (TestSearchPipeline pipeline = new TestSearchPipeline(
                sourceClient,
                filterFunction,
                rankFunction,
                filterCalls,
                rankCalls
        )) {
            SearchResultBox result = pipeline.search(QUERY).join();

            assertThat(result.result().products())
                    .containsExactly(PRODUCT_2);

            assertThat(result.result().offers()).containsExactly(OFFER_1);
            assertThat(result.result().reviews()).containsExactly(REVIEW_1);

            assertThat(result.errors()).isEmpty();

            assertThat(result.processingErrors()).hasSize(1);
            assertThat(result.processingErrors().get(0).processingStage())
                    .isEqualTo(ProcessingStage.RANK_PRODUCTS);
            assertThat(result.processingErrors().get(0).code())
                    .isEqualTo(ProcessingErrorCode.INTERNAL_ERROR);

            assertThat(result.isSuccessful()).isFalse();

            assertThat(filterCalls).hasValue(1);
            assertThat(rankCalls).hasValue(1);
        }
    }

    /**
     * Все три источника недоступны.
     *
     * Ожидаем:
     * - весь pipeline завершается AllSourcesUnavailableException;
     * - CPU processing не запускается.
     */
    @Test
    void shouldFailWhenAllSourcesAreUnavailable() {
        SearchSourceClient sourceClient = mock(SearchSourceClient.class);

        when(sourceClient.searchCatalog(anyString()))
                .thenReturn(CompletableFuture.failedFuture(
                        new IOException("Catalog unavailable")
                ));

        when(sourceClient.searchOffers(anyString()))
                .thenReturn(CompletableFuture.failedFuture(
                        new IOException("Offers unavailable")
                ));

        when(sourceClient.searchReviews(anyString()))
                .thenReturn(CompletableFuture.failedFuture(
                        new IOException("Reviews unavailable")
                ));

        AtomicInteger filterCalls = new AtomicInteger();
        AtomicInteger rankCalls = new AtomicInteger();

        try (TestSearchPipeline pipeline = new TestSearchPipeline(
                sourceClient,
                Function.identity(),
                Function.identity(),
                filterCalls,
                rankCalls
        )) {
            assertThatThrownBy(() -> pipeline.search(QUERY).join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(AllSourcesUnavailableException.class);

            assertThat(filterCalls).hasValue(0);
            assertThat(rankCalls).hasValue(0);
        }
    }

    private SearchSourceClient successfulSourceClient() {
        SearchSourceClient sourceClient = mock(SearchSourceClient.class);

        when(sourceClient.searchCatalog(anyString()))
                .thenReturn(CompletableFuture.completedFuture(List.of(PRODUCT_1)));

        when(sourceClient.searchOffers(anyString()))
                .thenReturn(CompletableFuture.completedFuture(List.of(OFFER_1)));

        when(sourceClient.searchReviews(anyString()))
                .thenReturn(CompletableFuture.completedFuture(List.of(REVIEW_1)));

        return sourceClient;
    }

    /**
     * Тестовый наследник позволяет подменять CPU processing
     * и проверять, какие стадии действительно запускались.
     */
    private static class TestSearchPipeline extends SearchPipeline {

        private final Function<List<Product>, List<Product>> filterFunction;
        private final Function<List<Product>, List<Product>> rankFunction;

        private final AtomicInteger filterCalls;
        private final AtomicInteger rankCalls;

        TestSearchPipeline(
                SearchSourceClient sourceClient,
                Function<List<Product>, List<Product>> filterFunction,
                Function<List<Product>, List<Product>> rankFunction,
                AtomicInteger filterCalls,
                AtomicInteger rankCalls
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
                    REQUEST_TIMEOUT,
                    2L,
                    new SourceTimeouts(
                            SOURCE_TIMEOUT,
                            SOURCE_TIMEOUT,
                            SOURCE_TIMEOUT
                    )
            );

            this.filterFunction = filterFunction;
            this.rankFunction = rankFunction;
            this.filterCalls = filterCalls;
            this.rankCalls = rankCalls;
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
