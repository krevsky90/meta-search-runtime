
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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SearchPipelineTest {

    private static final String QUERY = "iphone";

    private final SearchSourceClient sourceClient = mock(SearchSourceClient.class);

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

            assertThat(result.isSuccessful()).isTrue();
            assertThat(result.errors()).isEmpty();

            assertThat(result.result().products()).hasSize(2);
            assertThat(result.result().offers()).hasSize(1);
            assertThat(result.result().reviews()).hasSize(1);
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

            assertThat(result.isSuccessful()).isFalse();

            assertThat(result.result().products()).isEmpty();
            assertThat(result.result().offers()).hasSize(1);
            assertThat(result.result().reviews()).hasSize(1);

            assertThat(result.errors()).hasSize(1);

            assertThat(result.errors().getFirst().source()).isEqualTo("catalog");
            assertThat(ErrorCode.CONNECTION_FAILED).isEqualTo(result.errors().getFirst().code());
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

            assertThat(result.isSuccessful()).isFalse();

            assertThat(result.result().products()).isEmpty();
            assertThat(result.result().offers()).isEmpty();
            assertThat(result.result().reviews()).hasSize(1);

            assertThat(result.errors()).hasSize(2);

            assertThat(result.errors())
                    .extracting(SourceError::source)
                    .containsExactly("catalog", "offers");

            assertThat(result.errors())
                    .extracting(SourceError::code)
                    .containsExactly(ErrorCode.CONNECTION_FAILED, ErrorCode.TIMEOUT);
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

            ExecutionException exception = catchThrowableOfType(
                    () -> pipeline.search(QUERY).get(2, TimeUnit.SECONDS),
                    ExecutionException.class
            );

            assertThat(exception)
                    .isNotNull()    //additional check - enssure the exception was really caught
                    .hasCauseInstanceOf(AllSourcesUnavailableException.class);

            AllSourcesUnavailableException cause = (AllSourcesUnavailableException) exception.getCause();

            assertThat(cause.getErrors()).hasSize(3);
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
            assertThat(workerStarted.await(2, TimeUnit.SECONDS)).isTrue();

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
                try {
                    assertThatThrownBy(() ->
                            pipeline.search(QUERY).get(2, TimeUnit.SECONDS)
                    )
                            .isInstanceOf(ExecutionException.class)
                            .hasCauseInstanceOf(RejectedExecutionException.class);
                } finally {
                    // Что будет, если не вызвать countDown()?
                    //Рабочий поток останется заблокированным.
                    // При вызове pipeline.close() метод awaitTermination(terminationTimeout, ...) будет ждать завершения executor до terminationTimeout секунд.
                    // Затем shutdownNow() попытается прервать поток, но это уже будет нештатный путь завершения теста.

                    // Лучше освободить поток до закрытия Pipeline
                    // т.к. если close() ждёт завершения executor, он может ждать до terminationTimeout,
                    //      пока рабочий поток всё ещё заблокирован на releaseWorker.await().
                    releaseWorker.countDown();
                }
            }

        } finally {
            // На случай ошибки до создания Pipeline.
            releaseWorker.countDown();

            executor.shutdownNow();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
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

            assertThat(result.isSuccessful()).isTrue();
            assertThat(result.errors()).isEmpty();

            assertThat(result.result().products()).hasSize(2);
            assertThat(result.result().offers()).hasSize(1);
            assertThat(result.result().reviews()).hasSize(1);

            // Проверяем, что обе CPU-стадии выполнялись в нашем executor.
            assertThat(executedThreads).hasSize(2);

            assertThat(executedThreads.stream().allMatch(name -> name.startsWith("search-cpu-"))).isTrue();
        } finally {
//            executor.shutdownNow();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
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
            // Здесь есть два разных сценария:
            //- Pipeline завершился по своему deadline — получаем ExecutionException с причиной TimeoutException.
            //- Pipeline не завершился даже за секунду — сам get() выбросит TimeoutException.
            //Таким образом, тест отличает ожидаемое поведение Pipeline от зависания.
            assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
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
            try {
                // When
                CompletableFuture<SearchResultBox> result = pipeline.search("phone");

                // Then
                // Then
                // Здесь есть два разных сценария:
                //- Pipeline завершился по своему deadline — получаем ExecutionException с причиной TimeoutException.
                //- Pipeline не завершился даже за секунду — сам get() выбросит TimeoutException.
                //Таким образом, тест отличает ожидаемое поведение Pipeline от зависания.
                assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(TimeoutException.class);
            } finally {
                // Лучше освободить поток до закрытия Pipeline
                // т.к. если close() ждёт завершения executor, он может ждать до terminationTimeout,
                //      пока рабочий поток всё ещё заблокирован на releaseWorker.await().
                releaseWorker.countDown();
            }
        } finally {
            // На случай ошибки до создания Pipeline.
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
