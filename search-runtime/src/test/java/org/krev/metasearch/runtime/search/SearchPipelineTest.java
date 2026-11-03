
package org.krev.metasearch.runtime.search;

import org.junit.jupiter.api.Test;
import org.krev.metasearch.runtime.error_model.ErrorCode;
import org.krev.metasearch.runtime.error_model.SearchResultBox;
import org.krev.metasearch.runtime.error_model.SourceError;
import org.krev.metasearch.runtime.exceptions.AllSourcesUnavailableException;
import org.krev.metasearch.runtime.external.SearchSourceClient;
import org.krev.metasearch.runtime.model.Offer;
import org.krev.metasearch.runtime.model.Product;
import org.krev.metasearch.runtime.model.Review;

import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
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
                            .map(SourceError::source)
                            .toList()
            );

            assertEquals(
                    List.of(
                            ErrorCode.CONNECTION_FAILED,
                            ErrorCode.TIMEOUT
                    ),
                    result.errors().stream()
                            .map(SourceError::code)
                            .toList()
            );
        }
    }

    // --------------------------------------------------------
    // 4. All sources failed
    // --------------------------------------------------------

    @Test
    void allSourcesFailed() {
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

        Duration deadlineTimeout = Duration.ofMillis(500);
        long terminationTimeout = 5;

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
            try (SearchPipeline pipeline = new SearchPipeline(sourceClient, executor, deadlineTimeout, terminationTimeout)) {
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

    // --------------------------------------------------------
    // 6. Test executor threads
    // этот тест доказывает, что обе CPU-стадии выполнялись в выделенном пуле.
    // Но он не доказывает, что они выполнялись в разных потоках.
    // И это нормально — мы не должны требовать от executor такого поведения.
    // --------------------------------------------------------

    @Test
    void checkExecutorPool() throws Exception {
        ConcurrentLinkedQueue<String> executedThreads = new ConcurrentLinkedQueue<>();

        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("search-cpu-" + thread.threadId());
            return thread;
        };

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2,
                2,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy()
        ) {
            @Override
            protected void beforeExecute(Thread thread, Runnable task) {
                executedThreads.add(thread.getName());
                super.beforeExecute(thread, task);
            }
        };

        Duration deadlineTimeout = Duration.ofMillis(500);
        long terminationTimeout = 5;

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

        try (SearchPipeline pipeline = new SearchPipeline(sourceClient, executor, deadlineTimeout, terminationTimeout)) {

            SearchResultBox result = pipeline.search(QUERY)
                    .get(2, TimeUnit.SECONDS);

            assertTrue(result.isSuccessful());
            assertTrue(result.errors().isEmpty());

            assertEquals(2, result.result().products().size());
            assertEquals(1, result.result().offers().size());
            assertEquals(1, result.result().reviews().size());

            // Проверяем, что обе CPU-стадии выполнялись в нашем executor.
            assertEquals(2, executedThreads.size());

            assertTrue(executedThreads.stream().allMatch(name -> name.startsWith("search-cpu-")));
        } finally {
//            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    /**
     * проверка deadline-ов - start
     */

    // 1. Источник не отвечает — общий deadline
    // Проверяем:
    // - Catalog и Offers возвращают результаты.
    // - Reviews остаётся незавершённым.
    // - Общий deadline — 200 мс.
    // - Итоговый future завершается с TimeoutException.
    @Test
    void search_shouldTimeout_whenSourceDoesNotRespondBeforeDeadline() {
        // Given
        CompletableFuture<List<Product>> catalogFuture = CompletableFuture.completedFuture(List.of(new Product("p1")));
        CompletableFuture<List<Offer>> offersFuture = CompletableFuture.completedFuture(List.of(new Offer("o1")));
        CompletableFuture<List<Review>> reviewsFuture = new CompletableFuture<>();  // значит, ничего не отвечает, тупит

        SearchSourceClient sourceClient = mock(SearchSourceClient.class);

        when(sourceClient.searchCatalog(anyString())).thenReturn(catalogFuture);
        when(sourceClient.searchOffers(anyString())).thenReturn(offersFuture);
        when(sourceClient.searchReviews(anyString())).thenReturn(reviewsFuture);

        ThreadPoolExecutor cpuExecutor = new ThreadPoolExecutor(
                2,
                2,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(50),
                new ThreadPoolExecutor.AbortPolicy()
        );

        long terminationTimeout = 5;

        try (SearchPipeline pipeline = new SearchPipeline(sourceClient, cpuExecutor, Duration.ofMillis(200), terminationTimeout)) {
            // When
            CompletableFuture<SearchResultBox> result = pipeline.search("phone");

            // Then
            assertThatThrownBy(result::join)
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(TimeoutException.class);
        }
    }

    // CPU executor занят — общий deadline
    // Здесь проверяем важную особенность: даже если все источники ответили мгновенно,
    //      Pipeline не должен ждать бесконечно, пока освободится CPU executor.
    //
    // Займём единственный поток executor и заблокируем его через CountDownLatch.
    @Test
    void search_shouldTimeout_whenCpuExecutorIsBusy() throws InterruptedException {
        // Given
        SearchSourceClient sourceClient = mock(SearchSourceClient.class);

        when(sourceClient.searchCatalog(anyString()))
                .thenReturn(CompletableFuture.completedFuture(List.of(new Product("p1"))));

        when(sourceClient.searchOffers(anyString()))
                .thenReturn(CompletableFuture.completedFuture(List.of(new Offer("o1"))));

        when(sourceClient.searchReviews(anyString()))
                .thenReturn(CompletableFuture.completedFuture(List.of(new Review("r1"))));

        ThreadPoolExecutor cpuExecutor = createSingleThreadCpuExecutor();
        long terminationTimeout = 5;

        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);

        cpuExecutor.execute(() -> {
            workerStarted.countDown();

            try {
                releaseWorker.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertThat(workerStarted.await(2, TimeUnit.SECONDS)).isTrue();

        try (SearchPipeline pipeline = new SearchPipeline(sourceClient, cpuExecutor, Duration.ofMillis(200), terminationTimeout)) {
            // When
            CompletableFuture<SearchResultBox> result = pipeline.search("phone");

            // Then
            assertThatThrownBy(result::join)
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(TimeoutException.class);
        } finally {
            releaseWorker.countDown();
            cpuExecutor.shutdownNow();
            assertThat(cpuExecutor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    private ThreadPoolExecutor createSingleThreadCpuExecutor() {
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(50),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * проверка deadline-ов - end
     */
}
