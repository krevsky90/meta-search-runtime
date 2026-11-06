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

class SearchPipelineCancellationTest {
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

        CompletableFuture<SearchResultBox> result = pipeline.search(QUERY);

        // All sources are still pending.
        assertThat(executor.submittedTaskCount()).isZero();

        // Caller cancels the request.
        assertThat(result.cancel(true)).isTrue();

        // Sources complete after cancellation.
        catalogFuture.complete(List.of(new Product("product-1")));
        offersFuture.complete(List.of());
        reviewsFuture.complete(List.of());

        // CPU processing must not be submitted.
        assertThat(executor.submittedTaskCount()).isZero();
    }

    private SearchSourceClient successfulSourceClient() {
        SearchSourceClient sourceClient = mock(SearchSourceClient.class);

        when(sourceClient.searchCatalog(QUERY))
                .thenReturn(CompletableFuture.completedFuture(
                        List.of(new Product("product-1"))
                ));

        when(sourceClient.searchOffers(QUERY))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        when(sourceClient.searchReviews(QUERY))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        return sourceClient;
    }
}