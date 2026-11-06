package org.krev.metasearch.runtime.search;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class CancellationContextTest {

    @Test
    void cancel_shouldCancelAllRegisteredOperations() {
        CancellationContext context = new CancellationContext();

        CompletableFuture<String> result1 = new CompletableFuture<>();
        CompletableFuture<String> result2 = new CompletableFuture<>();

        Future<?> task1 = new TestFuture();
        Future<?> task2 = new TestFuture();

        assertThat(context.register(task1, result1)).isTrue();
        assertThat(context.register(task2, result2)).isTrue();

        context.cancel();

        assertThat(context.isCancelled()).isTrue();

        assertThat(task1.isCancelled()).isTrue();
        assertThat(task2.isCancelled()).isTrue();

        assertThat(result1.isCancelled()).isTrue();
        assertThat(result2.isCancelled()).isTrue();
    }

    @Test
    void cancel_shouldBeIdempotent() {
        CancellationContext context = new CancellationContext();

        TestFuture task = new TestFuture();
        CompletableFuture<String> result = new CompletableFuture<>();

        context.register(task, result);

        context.cancel();
        context.cancel();
        context.cancel();

        assertThat(context.isCancelled()).isTrue();
        assertThat(task.cancelCalls()).isEqualTo(1);
        assertThat(result.isCancelled()).isTrue();
    }

    @Test
    void register_afterCancellation_shouldImmediatelyCancelOperation() {
        CancellationContext context = new CancellationContext();

        context.cancel();

        TestFuture task = new TestFuture();
        CompletableFuture<String> result = new CompletableFuture<>();

        boolean registered = context.register(task, result);

        assertThat(registered).isFalse();
        assertThat(task.isCancelled()).isTrue();
        assertThat(result.isCancelled()).isTrue();
    }

    @Test
    void unregister_shouldExcludeOperationFromCancellation() {
        CancellationContext context = new CancellationContext();

        TestFuture task1 = new TestFuture();
        TestFuture task2 = new TestFuture();

        CompletableFuture<String> result1 = new CompletableFuture<>();
        CompletableFuture<String> result2 = new CompletableFuture<>();

        context.register(task1, result1);
        context.register(task2, result2);

        context.unregister(task1, result1);

        context.cancel();

        assertThat(task1.isCancelled()).isFalse();
        assertThat(result1.isCancelled()).isFalse();

        assertThat(task2.isCancelled()).isTrue();
        assertThat(result2.isCancelled()).isTrue();
    }

    @Test
    void unregister_shouldNotRemoveOperationWithDifferentResult() {
        CancellationContext context = new CancellationContext();

        TestFuture task = new TestFuture();

        CompletableFuture<String> registeredResult = new CompletableFuture<>();
        CompletableFuture<String> differentResult = new CompletableFuture<>();

        context.register(task, registeredResult);

        context.unregister(task, differentResult);

        context.cancel();

        assertThat(task.isCancelled()).isTrue();
        assertThat(registeredResult.isCancelled()).isTrue();
        assertThat(differentResult.isCancelled()).isFalse();
    }

    @Test
    void unregister_shouldNotRemoveOperationWithDifferentTask() {
        CancellationContext context = new CancellationContext();

        TestFuture registeredTask = new TestFuture();
        TestFuture differentTask = new TestFuture();

        CompletableFuture<String> result = new CompletableFuture<>();

        context.register(registeredTask, result);

        context.unregister(differentTask, result);

        context.cancel();

        assertThat(registeredTask.isCancelled()).isTrue();
        assertThat(differentTask.isCancelled()).isFalse();
        assertThat(result.isCancelled()).isTrue();
    }

    @Test
    void cancel_shouldNotChangeAlreadyCompletedResult() {
        CancellationContext context = new CancellationContext();

        TestFuture task = new TestFuture();
        CompletableFuture<String> result = CompletableFuture.completedFuture("OK");

        context.register(task, result);

        context.cancel();

        assertThat(task.isCancelled()).isTrue();
        assertThat(result.isCompletedExceptionally()).isFalse();
        assertThat(result.join()).isEqualTo("OK");
    }

    @Test
    void register_shouldRejectNullTask() {
        CancellationContext context = new CancellationContext();

        CompletableFuture<String> result = new CompletableFuture<>();

        assertThatCode(() -> context.register(null, result))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void register_shouldRejectNullResult() {
        CancellationContext context = new CancellationContext();

        Future<?> task = new TestFuture();

        assertThatCode(() -> context.register(task, null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * CancellationContext.cancel()
     *         |
     *         v
     * Future.cancel(true)
     *         |
     *         v
     * Thread.interrupt()
     *         |
     *         v
     * CountDownLatch.await()
     *         |
     *         v
     * InterruptedException
     *         |
     *         v
     * Рабочий поток завершается
     */
    @Test
    void cancel_shouldInterruptRegisteredTask() throws Exception {
        CancellationContext context = new CancellationContext();

        CountDownLatch workerStarted = new CountDownLatch(1);       // Сигнализирует, что поток действительно начал работу
        CountDownLatch workerInterrupted = new CountDownLatch(1);   // Сигнализирует, что поток получил прерывание
        AtomicBoolean workerCompleted = new AtomicBoolean(false);   // Показывает, что поток дошёл до завершения

        Thread worker = new Thread(() -> {
            workerStarted.countDown();

            try {
                new CountDownLatch(1).await();  // блокирует выполнение потока (чтобы мы могли прервать его, а он до тех пор не завершился)
            } catch (InterruptedException ex) {
                workerInterrupted.countDown();
                Thread.currentThread().interrupt();
            } finally {
                workerCompleted.set(true);
            }
        });

        worker.start();

        try {
            // Когда главный поток доходит до workerStarted.await(2, TimeUnit.SECONDS),
            // он видит, что счётчик уже равен 0.
            // Ждать не нужно — метод мгновенно возвращает true, и тест идёт дальше.
            // по сути получается, что 2 сек - таймаут на запуск потока worker.start()
            assertThat(workerStarted.await(2, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<Void> result = new CompletableFuture<>();

            // упрощённая реализация Future, которая представляет наш рабочий поток.
            Future<?> task = new Future<>() {
                @Override
                public boolean cancel(boolean mayInterruptIfRunning) {
                    // имитирует поведение отменяемой задачи: если передан true, вызывается interrupt() рабочего потока.
                    // Здесь мы проверяем именно взаимодействие CancellationContext с Future, а не внутреннюю реализацию FutureTask.
                    if (mayInterruptIfRunning) {
                        worker.interrupt();
                    }
                    return true;
                }

                @Override
                public boolean isCancelled() {
                    return false;
                }

                @Override
                public boolean isDone() {
                    return false;
                }

                @Override
                public Void get() {
                    return null;
                }

                @Override
                public Void get(long timeout, TimeUnit unit) {
                    return null;
                }
            };

            context.register(task, result);
            // operation.task().cancel(true) => worker.interrupt() => Поскольку рабочий поток находится внутри CountDownLatch.await(), он получает InterruptedException.
            // operation.result().cancel(false); => отменяет CompletableFuture, чтобы зависимые стадии асинхронной цепочки тоже получили сигнал об отмене.
            context.cancel();

            // проверка доказывает, что рабочий поток действительно получил прерывание.
            assertThat(workerInterrupted.await(2, TimeUnit.SECONDS)).isTrue();
            // результат операции также отменён.
            assertThat(result.isCancelled()).isTrue();

            // join() ждёт завершения потока. После этого проверяем, что поток завершился и выполнил finally.
            worker.join(2_000);

            assertThat(worker.isAlive()).isFalse();
            assertThat(workerCompleted).isTrue();
        } finally {
            worker.interrupt();
            worker.join(2_000);
        }
    }

    @Test
    void concurrentCancel_shouldCancelOperationOnlyOnce() throws Exception {
        CancellationContext context = new CancellationContext();

        TestFuture task = new TestFuture();
        CompletableFuture<String> result = new CompletableFuture<>();

        context.register(task, result);

        int threadCount = 10;
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            // Каждый поток:
            // 1. Уменьшает ready.
            // 2. Ждёт открытия общего стартового latch.
            // 3. Вызывает context.cancel().
            // 4. Уменьшает finished.
            new Thread(() -> {
                ready.countDown();

                try {
                    start.await();
                    context.cancel();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            }).start();
        }

        // гарантирует, что все десять потоков дошли до точки ожидания.
        assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
        // открывает latch, и все потоки получают возможность вызвать cancel() => конкуренция за один и тот же объект CancellationContext.
        start.countDown();

        assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(context.isCancelled()).isTrue();
        // то есть реальный cancel тасок в контексте сделал только один поток из 10,
        // к-ый первым успел войти в CancellationContext#cancel, когда cancelled был false
        assertThat(task.cancelCalls()).isEqualTo(1);
        assertThat(result.isCancelled()).isTrue();
    }

    private static class TestFuture implements Future<Object> {

        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicInteger cancelCalls = new AtomicInteger();

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelCalls.incrementAndGet();
            cancelled.set(true);
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public boolean isDone() {
            return cancelled.get();
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }

        int cancelCalls() {
            return cancelCalls.get();
        }
    }
}
