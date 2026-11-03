package org.krev.metasearch.runtime.search;

import org.krev.metasearch.runtime.error_model.ErrorCode;
import org.krev.metasearch.runtime.error_model.SearchBox;
import org.krev.metasearch.runtime.error_model.SearchResultBox;
import org.krev.metasearch.runtime.error_model.SourceError;
import org.krev.metasearch.runtime.exceptions.AllSourcesUnavailableException;
import org.krev.metasearch.runtime.exceptions.InvalidSourceResponseException;
import org.krev.metasearch.runtime.external.SearchSourceClient;
import org.krev.metasearch.runtime.model.Offer;
import org.krev.metasearch.runtime.model.Product;
import org.krev.metasearch.runtime.model.Review;
import org.krev.metasearch.runtime.model.SearchResult;

import java.io.IOException;
import java.net.ConnectException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Stream;

public class SearchPipeline implements AutoCloseable {
    private final SearchSourceClient sourceClient;
    private final ThreadPoolExecutor cpuExecutor;

    public SearchPipeline(SearchSourceClient sourceClient) {
        this(sourceClient, new ThreadPoolExecutor(
                8,
                8,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(50),
                new ThreadPoolExecutor.AbortPolicy()
        ));
    }

    SearchPipeline(
            SearchSourceClient sourceClient,
            ThreadPoolExecutor cpuExecutor
    ) {
        this.sourceClient = Objects.requireNonNull(sourceClient);
        this.cpuExecutor = Objects.requireNonNull(cpuExecutor);
    }

    // --------------------------------------------------------
    // 1. Normalize individual source result
    // --------------------------------------------------------

    private <T> CompletableFuture<SearchBox<List<T>>> searchGeneric(
            Function<String, CompletableFuture<List<T>>> searchFunction,
            String query,
            String source
    ) {
        try {
            CompletableFuture<List<T>> sourceFuture = Objects.requireNonNull(searchFunction.apply(query));
            return sourceFuture.handle((result, ex) -> {
                if (ex != null) {
                    return new SearchBox<>(
                            List.of(),
                            toSourceError(source, ex)
                    );
                }

                if (result == null) {
                    return new SearchBox<>(
                            List.of(),
                            toSourceError(
                                    source,
                                    new InvalidSourceResponseException("Source returned null result")
                            )
                    );
                }

                try {
                    return new SearchBox<>(
                            List.copyOf(result),
                            null
                    );
                } catch (RuntimeException validationError) {
                    return new SearchBox<>(
                            List.of(),
                            toSourceError(
                                    source,
                                    new InvalidSourceResponseException("Invalid source response", validationError)
                            )
                    );
                }
            });

        } catch (RuntimeException ex) {
            // Handle synchronous exception thrown before a future is returned.
            return CompletableFuture.completedFuture(
                    new SearchBox<>(
                            List.of(),
                            toSourceError(source, ex)
                    )
            );
        }
    }

    // --------------------------------------------------------
    // 2. Error handling
    // --------------------------------------------------------
    private Throwable unwrap(Throwable ex) {
        while ((ex instanceof CompletionException || ex instanceof ExecutionException) && ex.getCause() != null) {
            ex = ex.getCause();
        }

        return ex;
    }

    private SourceError toSourceError(String source, Throwable ex) {
        Throwable cause = unwrap(ex);
        return new SourceError(
                source,
                retrieveErrorCode(cause),
                cause.getMessage()
        );
    }

    private ErrorCode retrieveErrorCode(Throwable ex) {
        if (ex instanceof TimeoutException) {
            return ErrorCode.TIMEOUT;
        }

        if (ex instanceof IOException || ex instanceof ConnectException) {
            return ErrorCode.CONNECTION_FAILED;
        }

        if (ex instanceof InvalidSourceResponseException) {
            return ErrorCode.INVALID_RESPONSE;
        }

        return ErrorCode.INTERNAL_ERROR;
    }

    // --------------------------------------------------------
    // 3. Check source availability
    // --------------------------------------------------------

    private boolean allSourcesFailed(
            SearchBox<List<Product>> products,
            SearchBox<List<Offer>> offers,
            SearchBox<List<Review>> reviews
    ) {
        return !products.isSuccessful() && !offers.isSuccessful() && !reviews.isSuccessful();
    }

    // --------------------------------------------------------
    // 4. Collect source errors
    // --------------------------------------------------------
    private List<SourceError> collectErrors(
            SearchBox<List<Product>> products,
            SearchBox<List<Offer>> offers,
            SearchBox<List<Review>> reviews
    ) {
        return Stream.of(products.error(), offers.error(), reviews.error())
                .filter(Objects::nonNull)
                .toList();
    }

    // --------------------------------------------------------
    // 5. Product processing
    // --------------------------------------------------------
    private List<Product> filterProducts(List<Product> products) {
        System.out.println("filterProducts: " + Thread.currentThread().getName());
        // TODO: implement filtering
        return products;
    }

    private List<Product> rankProducts(List<Product> products) {
        System.out.println("rankProducts: " + Thread.currentThread().getName());
        // TODO: implement ranking
        return products;
    }

    // --------------------------------------------------------
    // 6. Combine partial results
    // --------------------------------------------------------
    private SearchResultBox combine(
            SearchBox<List<Product>> products,
            SearchBox<List<Offer>> offers,
            SearchBox<List<Review>> reviews
    ) {
        SearchResult result = new SearchResult(
                products.data(),
                offers.data(),
                reviews.data()
        );

        List<SourceError> errors = collectErrors(products, offers, reviews);
        return new SearchResultBox(result, errors);
    }

    // --------------------------------------------------------
    // 7. Main search method
    // --------------------------------------------------------
    public CompletableFuture<SearchResultBox> search(String query) {
        // Start all three independent requests.
        CompletableFuture<SearchBox<List<Product>>> products =
                searchGeneric(
                        sourceClient::searchCatalog,
                        query,
                        "catalog"
                );

        CompletableFuture<SearchBox<List<Offer>>> offers =
                searchGeneric(
                        sourceClient::searchOffers,
                        query,
                        "offers"
                );

        CompletableFuture<SearchBox<List<Review>>> reviews =
                searchGeneric(
                        sourceClient::searchReviews,
                        query,
                        "reviews"
                );

        // Wait until all three futures complete.

        return CompletableFuture.allOf(
                        products,
                        offers,
                        reviews
                )
                .thenCompose(unused -> {
                    // All futures have completed. join() does not block here.
                    SearchBox<List<Product>> productBox = products.join();
                    SearchBox<List<Offer>> offerBox = offers.join();
                    SearchBox<List<Review>> reviewBox = reviews.join();

                    // If every source failed, fail the entire pipeline.
                    if (allSourcesFailed(productBox, offerBox, reviewBox)) {
                        return CompletableFuture.failedFuture(
                                new AllSourcesUnavailableException(
                                        collectErrors(
                                                productBox,
                                                offerBox,
                                                reviewBox
                                        )
                                )
                        );
                    }

                    // Filter and rank products on the dedicated executor.
                    return CompletableFuture.supplyAsync(
                                    () -> filterProducts(productBox.data()),
                                    cpuExecutor
                            )
                            .thenApplyAsync(
                                    this::rankProducts,
                                    cpuExecutor
                            )
                            .thenApply(rankedProducts -> {
                                SearchBox<List<Product>> rankedProductBox =
                                        new SearchBox<>(
                                                rankedProducts,
                                                productBox.error()
                                        );

                                return combine(
                                        rankedProductBox,
                                        offerBox,
                                        reviewBox
                                );
                            });
                });
    }

    // --------------------------------------------------------
    // 8. Executor lifecycle
    // --------------------------------------------------------
    @Override
    public void close() {
        cpuExecutor.shutdown();
        try {
            if (!cpuExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                cpuExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            cpuExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}