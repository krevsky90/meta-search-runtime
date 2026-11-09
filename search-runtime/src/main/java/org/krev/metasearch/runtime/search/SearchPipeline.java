package org.krev.metasearch.runtime.search;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.krev.metasearch.runtime.config.SearchRuntimeConfig;
import org.krev.metasearch.runtime.error_model.*;
import org.krev.metasearch.runtime.exceptions.AllSourcesUnavailableException;
import org.krev.metasearch.runtime.exceptions.InvalidInputProcessingException;
import org.krev.metasearch.runtime.exceptions.InvalidSourceResponseException;
import org.krev.metasearch.runtime.external.SearchSourceClient;
import org.krev.metasearch.runtime.external.SourceCall;
import org.krev.metasearch.runtime.model.Offer;
import org.krev.metasearch.runtime.model.Product;
import org.krev.metasearch.runtime.model.Review;
import org.krev.metasearch.runtime.model.SearchResult;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Stream;

@Singleton
public class SearchPipeline implements AutoCloseable {
    private final SearchSourceClient sourceClient;
    private final ThreadPoolExecutor cpuExecutor;
    private final Duration requestTimeout;
    private final long terminationTimeoutSeconds;
    private final SourceTimeouts sourceTimeouts;

    @Inject
    public SearchPipeline(
            SearchSourceClient sourceClient,
            ThreadPoolExecutor cpuExecutor,
            SearchRuntimeConfig searchRuntimeConfig
    ) {
        this.sourceClient = Objects.requireNonNull(sourceClient);
        this.cpuExecutor = Objects.requireNonNull(cpuExecutor);

        Objects.requireNonNull(searchRuntimeConfig);

        this.requestTimeout = searchRuntimeConfig.requestTimeout();
        this.terminationTimeoutSeconds = searchRuntimeConfig.terminationTimeoutSeconds();
        this.sourceTimeouts = searchRuntimeConfig.sourceTimeouts();
    }

    // --------------------------------------------------------
    // 1. Normalize individual source result
    // --------------------------------------------------------
    private <T> CompletableFuture<SearchBox<List<T>>> searchGeneric(
            Function<String, SourceCall<List<T>>> function,
            String query,
            String source,
            Duration timeout,
            CancellationContext cancellationContext

    ) {
        SourceCall<List<T>> call = null;
        CancellationContext.Registration registration = null;
        try {
            call = Objects.requireNonNull(function.apply(query));
            registration = cancellationContext.register(call);
            SourceCall<List<T>> finalCall = call;

            CompletableFuture<List<T>> timedResult = Objects.requireNonNull(call.result())
                    .thenApply(Function.identity())
                    .orTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS);
            CompletableFuture<SearchBox<List<T>>> resultCf = timedResult.handle((result, ex) -> {
                Throwable cause = unwrap(ex);

                if (cause instanceof TimeoutException) {
                    try {
                        //means local deadline is exceeded -> cancel external call
                        finalCall.cancelTransport();
                    } catch (RuntimeException cancellationError) {
                        // TODO: log cancellation failure
                    }
                }
                if (cause != null) {
                    return new SearchBox<>(
                            List.of(),
                            toSourceError(source, cause)
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

            CancellationContext.Registration finalRegistration = registration;
            resultCf.whenComplete((result, ex) -> {
                finalRegistration.unregister();
            });

            return resultCf;
        } catch (RuntimeException ex) {
            // if call.result() throws Exception or returns null, then we will jump to current catch
            // if registration exists, we have to remove it (otherwise it will blow up cancellationContext => memory leak)
            if (registration != null) {
                try {
                    call.cancel();
                } catch (RuntimeException cancellationError) {
                    // TODO: log cancellation failure
                } finally {
                    registration.unregister();
                }
            }

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
    // 1.2 Normalize individual processing result
    // --------------------------------------------------------
    private <T> CompletableFuture<ProcessingBox<List<T>>> processingGeneric(
            Function<List<T>, List<T>> processingFunction,
            List<T> input,
            List<T> fallback,
            ProcessingStage stage,
            CancellationContext cancellationContext
    ) {
        CompletableFuture<List<T>> cf = new CompletableFuture<>();
        FutureTask<List<T>> ft = new FutureTask<>(() -> processingFunction.apply(input)) {
            // done() вызывается самим FutureTask в ЛЮБОМ СЛУЧАЕ после того, как он завершился: успешно, с исключением или отменой.
            // Поэтому get() уже не ожидает выполнения задачи, а сразу возвращает результат или выбрасывает исключение.
            @Override
            protected void done() {
                try {
                    cf.complete(get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cf.completeExceptionally(e);
                } catch (ExecutionException e) {
                    cf.completeExceptionally(e.getCause());
                } catch (CancellationException e) {
                    cf.cancel(false);
                }
            }
        };

        CancellationContext.Registration registration = cancellationContext.register(ft, cf);

        CompletableFuture<ProcessingBox<List<T>>> finalCf =
                cf.handle((result, ex) -> {
                    registration.unregister();

                    Throwable cause = unwrap(ex);

                    if (cause instanceof CancellationException cancellationException) {
                        throw cancellationException;
                    }

                    if (ex != null) {
                        return new ProcessingBox<>(
                                fallback,
                                toProcessingError(stage, cause)
                        );
                    }

                    if (result == null) {
                        return new ProcessingBox<>(
                                fallback,
                                toProcessingError(
                                        stage,
                                        new InvalidInputProcessingException("Processing returned null result")
                                )
                        );
                    }

                    try {
                        return new ProcessingBox<>(
                                List.copyOf(result),    // might throw NPE
                                null
                        );
                    } catch (RuntimeException validationError) {
                        return new ProcessingBox<>(
                                fallback,
                                toProcessingError(
                                        stage,
                                        new InvalidInputProcessingException("Invalid processing result", validationError)
                                )
                        );
                    }
                });


        if (registration.isActive()) {
            // The operation has not been cancelled or unregistered yet.
            try {
                cpuExecutor.execute(ft);
            } catch (RejectedExecutionException ex) {
                // Handle synchronous exception thrown before a future is returned.
                // For example, RejectedExecutionException
                // + unregister(ft, cf). NOTE: if we do not do that and cpuExecutor throws Exception =>
                //      cancellationContext.unregister(ft, cf); won't be executed in handle =>
                //      cancellationContext will grow => memory leak!
                registration.unregister();

                cf.completeExceptionally(ex);
            }
        }

        return finalCf;
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

        if (ex instanceof IOException) { // || ex instanceof ConnectException
            return ErrorCode.CONNECTION_FAILED;
        }

        if (ex instanceof InvalidSourceResponseException) {
            return ErrorCode.INVALID_RESPONSE;
        }

        return ErrorCode.INTERNAL_ERROR;
    }

    private ProcessingError toProcessingError(ProcessingStage stage, Throwable ex) {
        Throwable cause = unwrap(ex);
        return new ProcessingError(
                stage,
                retrieveProcessingErrorCode(cause),
                cause.getMessage()
        );
    }

    private ProcessingErrorCode retrieveProcessingErrorCode(Throwable ex) {
        if (ex instanceof TimeoutException) {
            return ProcessingErrorCode.TIMEOUT;
        }

        if (ex instanceof InvalidInputProcessingException) {
            return ProcessingErrorCode.INVALID_INPUT;
        }

        return ProcessingErrorCode.INTERNAL_ERROR;
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
        return Stream.of(
                        products.error(),
                        offers.error(),
                        reviews.error()
                )
                .filter(Objects::nonNull)
                .toList();
    }

    // --------------------------------------------------------
    // 4.2 Collect processing errors
    // --------------------------------------------------------
    private List<ProcessingError> collectProcessingErrors(
            ProcessingBox<List<Product>> products
    ) {
        return Stream.of(
                        products.error()
                )
                .filter(Objects::nonNull)
                .toList();
    }

    // --------------------------------------------------------
    // 5. Product processing
    // --------------------------------------------------------
    List<Product> filterProducts(List<Product> products) {
        System.out.println("filterProducts: " + Thread.currentThread().getName());
        // TODO: implement filtering
        return products;
    }

    List<Product> rankProducts(List<Product> products) {
        System.out.println("rankProducts: " + Thread.currentThread().getName());
        // TODO: implement ranking
        return products;
    }

    // --------------------------------------------------------
    // 6. Combine partial results
    // --------------------------------------------------------
    private SearchResultBox combine(
            SearchBox<List<Product>> productsSourceBox,
            ProcessingBox<List<Product>> products,
            SearchBox<List<Offer>> offers,
            SearchBox<List<Review>> reviews
    ) {
        SearchResult result = new SearchResult(
                products.data(),
                offers.data(),
                reviews.data()
        );

        List<SourceError> errors = collectErrors(productsSourceBox, offers, reviews);
        List<ProcessingError> processingErrors = collectProcessingErrors(products);

        return new SearchResultBox(result, errors, processingErrors);
    }

    // --------------------------------------------------------
    // 7. Main search method
    // --------------------------------------------------------
    public CompletableFuture<SearchResultBox> search(String query) {
        // 1 query -> 1 context
        CancellationContext cancellationContext = new CancellationContext();

        // NOTE: nanoTime() предназначен для измерения интервалов времени. Он монотонный
        long deadlineNanos = System.nanoTime() + requestTimeout.toNanos();

        // Start all three independent requests.
        // catalog
        CompletableFuture<SearchBox<List<Product>>> products =
                searchGeneric(
                        sourceClient::searchCatalog,
                        query,
                        "catalog",
                        sourceTimeouts.catalog(),
                        cancellationContext
                );

        // offers
        CompletableFuture<SearchBox<List<Offer>>> offers =
                searchGeneric(
                        sourceClient::searchOffers,
                        query,
                        "offers",
                        sourceTimeouts.offers(),
                        cancellationContext
                );

        //reviews
        CompletableFuture<SearchBox<List<Review>>> reviews =
                searchGeneric(
                        sourceClient::searchReviews,
                        query,
                        "reviews",
                        sourceTimeouts.reviews(),
                        cancellationContext
                );

        // Wait until all three futures complete.
        CompletableFuture<SearchResultBox> result = CompletableFuture.allOf(
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

                            // If catalog failed, skip product processing.
                            if (!productBox.isSuccessful()) {
                                ProcessingBox<List<Product>> processingBox = new ProcessingBox<>(List.of(), null);
                                return CompletableFuture.completedFuture(
                                        combine(productBox, processingBox, offerBox, reviewBox)
                                );
                            }

                            // 1. filtering
                            CompletableFuture<ProcessingBox<List<Product>>> filteredProducts =
                                    processingGeneric(this::filterProducts,
                                            productBox.data(),
                                            List.of(),
                                            ProcessingStage.FILTER_PRODUCTS,
                                            cancellationContext
                                    );


                            //2. ranking
                            CompletableFuture<ProcessingBox<List<Product>>> rankedProducts =
                                    filteredProducts.thenCompose(filterBox -> {
                                        // If filtering failed, skip ranking.
                                        if (!filterBox.isSuccessful()) {
                                            return CompletableFuture.completedFuture(filterBox);
                                        }

                                        return processingGeneric(this::rankProducts,
                                                filterBox.data(),
                                                filterBox.data(),
                                                ProcessingStage.RANK_PRODUCTS,
                                                cancellationContext
                                        );
                                    });

                            // 3. Combine
                            return rankedProducts.thenApply(rankedBox ->
                                    combine(
                                            productBox,
                                            rankedBox,
                                            offerBox,
                                            reviewBox
                                    )
                            );
                        }
                )
                .orTimeout(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);

        // NOTE: whenComplete created NEW CompletableFuture => we should return 'result' Cf, but not Cf that was created by whenComplete.
        // otherwise 'cancel' by user side will cancel whenComplete's Cf, but not 'result' Cf !!!
        result.whenComplete((searchResultBox, ex) -> {
            Throwable cause = unwrap(ex);
            if (cause instanceof TimeoutException || cause instanceof CancellationException) {
                cancellationContext.cancel();
            }
        });

        return result;
    }

    // --------------------------------------------------------
// 8. Executor lifecycle
// --------------------------------------------------------
    @Override
    public void close() {
        cpuExecutor.shutdown();
        try {
            if (!cpuExecutor.awaitTermination(terminationTimeoutSeconds, TimeUnit.SECONDS)) {
                cpuExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            cpuExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}