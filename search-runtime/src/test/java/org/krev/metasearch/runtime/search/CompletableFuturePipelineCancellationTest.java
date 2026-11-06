package org.krev.metasearch.runtime.search;

import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

public class CompletableFuturePipelineCancellationTest {
    // Главный вывод: future отменён, но задача продолжила выполняться.
    // И ещё одна тонкость:
    // CompletableFuture не хранит ссылку на Future, возвращаемый ExecutorService.submit().
    // Поэтому cancel(true) у CompletableFuture, созданного через supplyAsync(), не имеет механизма для прерывания выполняющегося worker.
    @Test
    void taskContinuesEvenIfFutureIsCancelled() throws InterruptedException {
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

        // получил ли worker InterruptedException
        AtomicBoolean interrupted = new AtomicBoolean(false);
        // завершил ли worker выполнение
        AtomicBoolean workerCompleted = new AtomicBoolean(false);

        try {
            CompletableFuture<String> cf = CompletableFuture.supplyAsync(() -> {
                workerStarted.countDown();

                try {
                    releaseWorker.await();
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                } finally {
                    workerCompleted.set(true);
                }

                return "someString";
            }, executor);

            // Дожидаемся фактического старта worker.
            assertThat(workerStarted.await(2, TimeUnit.SECONDS)).isTrue();

            // Отменяем уже выполняющуюся задачу.
            boolean cancelled = cf.cancel(true);

            assertThat(cancelled).isTrue();
            assertThat(cf.isCancelled()).isTrue();

            // Проверяем, что worker пока не прерван и не завершён.
            assertThat(interrupted.get()).isFalse();
            assertThat(workerCompleted.get()).isFalse();

            // Разрешаем worker продолжить выполнение.
            releaseWorker.countDown();

            executor.shutdown();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();

            // Проверяем, что worker всё-таки выполнил свою задачу.
            assertThat(interrupted.get()).isFalse();
            assertThat(workerCompleted.get()).isTrue();

            // При этом future по-прежнему отменён.
            assertThat(cf.isCancelled()).isTrue();
        } finally {
            releaseWorker.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void testThenApply_checkIsDoneIsCancelledIsCompletedExceptionally_WhenSourceIsCancelled() {
        CompletableFuture<String> source = new CompletableFuture<>();
        CompletableFuture<Integer> stage1 = source.thenApply(String::length);
        CompletableFuture<String> stage2 = stage1.thenApply(length -> "Length: " + length);

        source.cancel(true);

        assertThat(source.isDone()).isTrue();
        assertThat(stage1.isDone()).isTrue();
        assertThat(stage2.isDone()).isTrue();

        assertThat(source.isCancelled()).isTrue();
        assertThat(stage1.isCancelled()).isFalse();
        assertThat(stage2.isCancelled()).isFalse();

        assertThat(source.isCompletedExceptionally()).isTrue();
        assertThat(stage1.isCompletedExceptionally()).isTrue();
        assertThat(stage2.isCompletedExceptionally()).isTrue();
    }

    @Test
    void testThenApply_checkIsDoneIsCancelledIsCompletedExceptionally_WhenStage1IsCancelled() {
        CompletableFuture<String> source = new CompletableFuture<>();
        CompletableFuture<Integer> stage1 = source.thenApply(String::length);
        CompletableFuture<String> stage2 = stage1.thenApply(length -> "Length: " + length);

        stage1.cancel(true);

        assertThat(source.isDone()).isFalse();
        assertThat(stage1.isDone()).isTrue();
        assertThat(stage2.isDone()).isTrue();

        assertThat(source.isCancelled()).isFalse();
        assertThat(stage1.isCancelled()).isTrue();
        assertThat(stage2.isCancelled()).isFalse();

        assertThat(source.isCompletedExceptionally()).isFalse();
        assertThat(stage1.isCompletedExceptionally()).isTrue();
        assertThat(stage2.isCompletedExceptionally()).isTrue();
    }

    @Test
    void testThenApply_checkIsDoneIsCancelledIsCompletedExceptionally_WhenStage2IsCancelled() {
        CompletableFuture<String> source = new CompletableFuture<>();
        CompletableFuture<Integer> stage1 = source.thenApply(String::length);
        CompletableFuture<String> stage2 = stage1.thenApply(length -> "Length: " + length);

        stage2.cancel(true);

        assertThat(source.isDone()).isFalse();
        assertThat(stage1.isDone()).isFalse();
        assertThat(stage2.isDone()).isTrue();

        assertThat(source.isCancelled()).isFalse();
        assertThat(stage1.isCancelled()).isFalse();
        assertThat(stage2.isCancelled()).isTrue();

        assertThat(source.isCompletedExceptionally()).isFalse();
        assertThat(stage1.isCompletedExceptionally()).isFalse();
        assertThat(stage2.isCompletedExceptionally()).isTrue();
    }

    @Test
    void testThenCompose_checkIsDoneIsCancelledIsCompletedExceptionally_WhenPipelineIsCancelled() {
        CompletableFuture<String> source = new CompletableFuture<>();
        CompletableFuture<String> inner = new CompletableFuture<>();
        CompletableFuture<String> pipeline = source.thenCompose(value -> inner);

        pipeline.cancel(true);

        assertThat(source.isDone()).isFalse();
        assertThat(inner.isDone()).isFalse();
        assertThat(pipeline.isDone()).isTrue();

        assertThat(source.isCancelled()).isFalse();
        // NOTE: inner вообще не выполнялась! т.к. source еще не завершен => thenCompose еще не получил значение value, и не вызвал inner
        assertThat(inner.isCancelled()).isFalse();
        assertThat(pipeline.isCancelled()).isTrue();

        assertThat(source.isCompletedExceptionally()).isFalse();
        assertThat(inner.isCompletedExceptionally()).isFalse();
        assertThat(pipeline.isCompletedExceptionally()).isTrue();
    }

    @Test
    void testThenCompose_checkIsDoneIsCancelledIsCompletedExceptionally_WhenInnerIsCancelled() {
        CompletableFuture<String> source = new CompletableFuture<>();
        CompletableFuture<String> inner = new CompletableFuture<>();
        CompletableFuture<String> pipeline = source.thenCompose(value -> inner);

        inner.cancel(true);

        assertThat(source.isDone()).isFalse();
        assertThat(inner.isDone()).isTrue();
        assertThat(pipeline.isDone()).isFalse();

        assertThat(source.isCancelled()).isFalse();
        assertThat(inner.isCancelled()).isTrue();
        assertThat(pipeline.isCancelled()).isFalse();

        assertThat(source.isCompletedExceptionally()).isFalse();
        assertThat(inner.isCompletedExceptionally()).isTrue();
        // NOTE: pipeline не завершился исключением, потому что чтобы thenCompose() начал отслеживать результат inner,
        // сначала должен завершиться source (а он ещё не завершён).
        // Поэтому pipeline пока даже не знает, что ему предстоит получить именно inner
        assertThat(pipeline.isCompletedExceptionally()).isFalse();
    }

    @Test
    void testThenCompose_checkIsDoneIsCancelledIsCompletedExceptionally_WhenInnerIsCancelledAndSourceIsCompleted() {
        CompletableFuture<String> source = new CompletableFuture<>();
        CompletableFuture<String> inner = new CompletableFuture<>();
        CompletableFuture<String> pipeline = source.thenCompose(value -> inner);

        inner.cancel(true);

        assertThat(source.isDone()).isFalse();
        assertThat(inner.isDone()).isTrue();
        assertThat(pipeline.isDone()).isFalse();

        assertThat(source.isCancelled()).isFalse();
        assertThat(inner.isCancelled()).isTrue();
        assertThat(pipeline.isCancelled()).isFalse();

        assertThat(source.isCompletedExceptionally()).isFalse();
        assertThat(inner.isCompletedExceptionally()).isTrue();
        // NOTE: pipeline не завершился исключением, потому что чтобы thenCompose() начал отслеживать результат inner,
        // сначала должен завершиться source (а он ещё не завершён).
        // Поэтому pipeline пока даже не знает, что ему предстоит получить именно inner
        assertThat(pipeline.isCompletedExceptionally()).isFalse();

        // additional: complete source
        source.complete("query");

        assertThat(source.isDone()).isTrue();
        assertThat(inner.isDone()).isTrue();
        assertThat(pipeline.isDone()).isTrue();

        assertThat(source.isCancelled()).isFalse();
        assertThat(inner.isCancelled()).isTrue();
        assertThat(pipeline.isCancelled()).isFalse();

        // source.complete("query") успешно завершает source
        // => Запускается функция value -> inner.
        // => thenCompose() получает уже отменённый inner.
        // => Поэтому pipeline завершается исключительно.
        assertThat(pipeline.isCompletedExceptionally()).isTrue();
    }
}