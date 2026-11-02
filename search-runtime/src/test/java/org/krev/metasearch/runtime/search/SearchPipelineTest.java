
package org.krev.metasearch.runtime.search;

import org.junit.jupiter.api.Test;
import org.krev.metasearch.runtime.error_model.ErrorCode;
import org.krev.metasearch.runtime.error_model.SearchResultBox;
import org.krev.metasearch.runtime.exceptions.AllSourcesUnavailableException;
import org.krev.metasearch.runtime.external.SearchSourceClient;
import org.krev.metasearch.runtime.model.Offer;
import org.krev.metasearch.runtime.model.Product;
import org.krev.metasearch.runtime.model.Review;

import java.io.IOException;
import java.net.ConnectException;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SearchPipelineTest {

    private static final String QUERY = "iphone";

    private final SearchSourceClient sourceClient =
            mock(SearchSourceClient.class);

    // --------------------------------------------------------
    // 1. All sources successful
    // --------------------------------------------------------

    @Test
    void allSourcesSuccessful() throws Exception {

        when(sourceClient.searchCatalog(QUERY))
                .thenReturn(CompletableFuture.completedFuture(
                        List.of(new Product("p1"), new Product("p2"))
                ));

        when(sourceClient.searchOffers(QUERY))
                .thenReturn(CompletableFuture.completedFuture(
                        List.of(new Offer("o1"))
                ));

        when(sourceClient.searchReviews(QUERY))
                .thenReturn(CompletableFuture.completedFuture(
                        List.of(new Review("r1"))
                ));

        try (SearchPipeline pipeline = new SearchPipeline(sourceClient)) {

            SearchResultBox result = pipeline.search(QUERY)
                    .get(2, TimeUnit.SECONDS);

            assertTrue(result.isSuccessful());
            assertTrue(result.errors().isEmpty());

            assertEquals(2, result.result().products().size());
            assertEquals(1, result.result().offers().size());
            assertEquals(1, result.result().reviews().size());
        }
    }

    // --------------------------------------------------------
    // 2. One source failed
    // --------------------------------------------------------
    @Test
    void oneSourceFailed() throws Exception {
        when(sourceClient.searchCatalog(QUERY))
                .thenReturn(CompletableFuture.failedFuture(
                        new ConnectException("Catalog unavailable")
                ));

        when(sourceClient.searchOffers(QUERY))
                .thenReturn(CompletableFuture.completedFuture(
                        List.of(new Offer("o1"))
                ));

        when(sourceClient.searchReviews(QUERY))
                .thenReturn(CompletableFuture.completedFuture(
                        List.of(new Review("r1"))
                ));

        try (SearchPipeline pipeline = new SearchPipeline(sourceClient)) {
            SearchResultBox result = pipeline.search(QUERY)
                    .get(2, TimeUnit.SECONDS);

            assertFalse(result.isSuccessful());

            assertTrue(result.result().products().isEmpty());
            assertEquals(1, result.result().offers().size());
            assertEquals(1, result.result().reviews().size());

            assertEquals(1, result.errors().size());

            assertEquals("catalog", result.errors().getFirst().source());
            assertEquals(
                    ErrorCode.CONNECTION_FAILED,
                    result.errors().getFirst().code()
            );
        }
    }

    // --------------------------------------------------------
    // 3. Two sources failed
    // --------------------------------------------------------

    @Test
    void twoSourcesFailed() throws Exception {

        when(sourceClient.searchCatalog(QUERY))
                .thenReturn(CompletableFuture.failedFuture(
                        new ConnectException("Catalog unavailable")
                ));

        when(sourceClient.searchOffers(QUERY))
                .thenReturn(CompletableFuture.failedFuture(
                        new TimeoutException("Offers timeout")
                ));

        when(sourceClient.searchReviews(QUERY))
                .thenReturn(CompletableFuture.completedFuture(
                        List.of(new Review("r1"))
                ));

        try (SearchPipeline pipeline = new SearchPipeline(sourceClient)) {
            SearchResultBox result = pipeline.search(QUERY)
                    .get(2, TimeUnit.SECONDS);

            assertFalse(result.isSuccessful());

            assertTrue(result.result().products().isEmpty());
            assertTrue(result.result().offers().isEmpty());
            assertEquals(1, result.result().reviews().size());

            assertEquals(2, result.errors().size());

            assertEquals(
                    List.of("catalog", "offers"),
                    result.errors().stream()
                            .map(error -> error.source())
                            .toList()
            );

            assertEquals(
                    List.of(
                            ErrorCode.CONNECTION_FAILED,
                            ErrorCode.TIMEOUT
                    ),
                    result.errors().stream()
                            .map(error -> error.code())
                            .toList()
            );
        }
    }

    // --------------------------------------------------------
    // 4. All sources failed
    // --------------------------------------------------------

    @Test
    void allSourcesFailed() throws Exception {

        when(sourceClient.searchCatalog(QUERY))
                .thenReturn(CompletableFuture.failedFuture(
                        new ConnectException("Catalog unavailable")
                ));

        when(sourceClient.searchOffers(QUERY))
                .thenReturn(CompletableFuture.failedFuture(
                        new TimeoutException("Offers timeout")
                ));

        when(sourceClient.searchReviews(QUERY))
                .thenReturn(CompletableFuture.failedFuture(
                        new IOException("Reviews unavailable")
                ));

        try (SearchPipeline pipeline = new SearchPipeline(sourceClient)) {

            ExecutionException exception = assertThrows(
                    ExecutionException.class,
                    () -> pipeline.search(QUERY)
                            .get(2, TimeUnit.SECONDS)
            );

            assertInstanceOf(
                    AllSourcesUnavailableException.class,
                    exception.getCause()
            );

            AllSourcesUnavailableException cause =
                    (AllSourcesUnavailableException) exception.getCause();

            assertEquals(3, cause.getErrors().size());
        }
    }

    // --------------------------------------------------------
    // 5. Ranking executor rejected task
    // --------------------------------------------------------

    @Test
    void rankingExecutorRejected() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.AbortPolicy()
        );

        //workerStarted	Сообщить тесту, что рабочий поток действительно начал выполнение
        //releaseWorker	Не позволить рабочему потоку завершиться, пока тест этого не разрешит
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);

        try {
            // Occupy the only worker.
            executor.execute(() -> {
                workerStarted.countDown();

                try {
                    releaseWorker.await();  //blocks the only thread of executor (since this latch = 1)
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            // checks that worker is really started (=> workerStarted.countDown() happened => its latch = 0 => 'await' returns true)
            assertTrue(workerStarted.await(2, TimeUnit.SECONDS));

            // Fill the only queue slot (since queue capacity = 1)
            executor.execute(() -> {
                // Intentionally empty.
            });

            //NOW worker is busy and queue is full
            // => the next task will be rejected!

            when(sourceClient.searchCatalog(QUERY))
                    .thenReturn(CompletableFuture.completedFuture(
                            List.of(new Product("p1"))
                    ));

            when(sourceClient.searchOffers(QUERY))
                    .thenReturn(CompletableFuture.completedFuture(
                            List.of(new Offer("o1"))
                    ));

            when(sourceClient.searchReviews(QUERY))
                    .thenReturn(CompletableFuture.completedFuture(
                            List.of(new Review("r1"))
                    ));

            // try to execute the next task and get rejection
            try (SearchPipeline pipeline = new SearchPipeline(sourceClient, executor)) {
                ExecutionException exception = assertThrows(
                        ExecutionException.class,
                        () -> pipeline.search(QUERY)
                                .get(2, TimeUnit.SECONDS)
                );

                assertInstanceOf(
                        RejectedExecutionException.class,
                        exception.getCause()
                );
            }

        } finally {
            // Что будет, если не вызвать countDown()?
            //Рабочий поток останется заблокированным.
            // При вызове pipeline.close() метод awaitTermination(30, ...) будет ждать завершения executor до 30 секунд.
            // Затем shutdownNow() попытается прервать поток, но это уже будет нештатный путь завершения теста.
            releaseWorker.countDown();

            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}
