package org.krev.metasearch.runtime.search;

import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
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

        assertThat(context.register(task1, result1).isActive()).isTrue();
        assertThat(context.register(task2, result2).isActive()).isTrue();

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

        CancellationContext.Registration registration = context.register(task, result);

        assertThat(registration.isActive()).isFalse();
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

        CancellationContext.Registration registration1 = context.register(task1, result1);
        CancellationContext.Registration registration2 = context.register(task2, result2);

        registration1.unregister();

        context.cancel();

        assertThat(task1.isCancelled()).isFalse();
        assertThat(result1.isCancelled()).isFalse();

        assertThat(task2.isCancelled()).isTrue();
        assertThat(result2.isCancelled()).isTrue();

        // обе регистрации неактивны: первая — потому что её сняли, вторая — потому что контекст отменили.
        assertThat(registration1.isActive()).isFalse();
        assertThat(registration2.isActive()).isFalse();
    }

    @Test
    void unregister_shouldNotAffectOtherRegistrationWithSameTask() {
        CancellationContext context = new CancellationContext();

        TestFuture task = new TestFuture();

        CompletableFuture<String> result1 = new CompletableFuture<>();
        CompletableFuture<String> result2 = new CompletableFuture<>();

        CancellationContext.Registration registration1 = context.register(task, result1);
        CancellationContext.Registration registration2 = context.register(task, result2);

        registration1.unregister();

        context.cancel();

        assertThat(registration1.isActive()).isFalse();
        assertThat(registration2.isActive()).isFalse();

        assertThat(result1.isCancelled()).isFalse();
        assertThat(result2.isCancelled()).isTrue();
    }

    @Test
    void unregister_shouldNotAffectOtherRegistrationWithSameResult() {
        CancellationContext context = new CancellationContext();

        TestFuture task1 = new TestFuture();
        TestFuture task2 = new TestFuture();

        CompletableFuture<String> result = new CompletableFuture<>();

        CancellationContext.Registration registration1 = context.register(task1, result);
        CancellationContext.Registration registration2 = context.register(task2, result);

        registration1.unregister();

        context.cancel();

        assertThat(registration1.isActive()).isFalse();
        assertThat(registration2.isActive()).isFalse();

        assertThat(task1.isCancelled()).isFalse();
        assertThat(task2.isCancelled()).isTrue();

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
     * |
     * v
     * Future.cancel(true)
     * |
     * v
     * Thread.interrupt()
     * |
     * v
     * CountDownLatch.await()
     * |
     * v
     * InterruptedException
     * |
     * v
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

    /**
     * В коде
     * if (registration.isActive()) {
     * // Здесь другой поток может вызвать context.cancel().
     * cpuExecutor.execute(task);
     * }
     * <p>
     * Проверка isActive() и вызов execute() не атомарны.
     * Гарантия, которую мы хотим проверить:
     * даже если отмена произошла в промежутке между проверкой и отправкой задачи в executor, пользовательская CPU-операция не стартует.
     */
    @Test
    void cancelledFutureTaskShouldNotStartWhenCancellationHappensBeforeRun() {
        CancellationContext context = new CancellationContext();

        AtomicBoolean processingStarted = new AtomicBoolean();

        CompletableFuture<String> result = new CompletableFuture<>();

        FutureTask<String> task = new FutureTask<>(() -> {
            processingStarted.set(true);
            return "processed";
        }) {
            @Override
            protected void done() {
                try {
                    result.complete(get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    result.completeExceptionally(e);
                } catch (ExecutionException e) {
                    result.completeExceptionally(e.getCause());
                } catch (java.util.concurrent.CancellationException e) {
                    result.cancel(false);
                }
            }
        };

        CancellationContext.Registration registration = context.register(task, result);

        // Simulate the check in processingGeneric().
        assertThat(registration.isActive()).isTrue();

        // Global cancellation happens after isActive(), but before execute().
        context.cancel();

        // Simulate cpuExecutor.execute(task).
        task.run();

        assertThat(processingStarted).isFalse();
        assertThat(task.isCancelled()).isTrue();
        assertThat(result).isCancelled();
        assertThat(context.isCancelled()).isTrue();
    }

    @Test
    void unregister_shouldBeIdempotent() {
        CancellationContext context = new CancellationContext();

        TestFuture task = new TestFuture();
        CompletableFuture<String> result = new CompletableFuture<>();

        CancellationContext.Registration registration = context.register(task, result);

        registration.unregister();
        registration.unregister();

        context.cancel();

        assertThat(registration.isActive()).isFalse();
        assertThat(task.isCancelled()).isFalse();
        assertThat(result.isCancelled()).isFalse();
    }

    @Test
    void cancel_shouldInterruptRunningProcessingTask() throws InterruptedException {
        CancellationContext context = new CancellationContext();

        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskInterrupted = new CountDownLatch(1);
        CountDownLatch workerFinished = new CountDownLatch(1);

        CompletableFuture<String> result = new CompletableFuture<>();

        FutureTask<String> task = new FutureTask<>(() -> {
            taskStarted.countDown();

            try {
                new CountDownLatch(1).await();
                return "completed";
            } catch (InterruptedException ex) {
                taskInterrupted.countDown();
                throw ex;
            } finally {
                workerFinished.countDown();
            }
        }) {
            // done() вызывается самим FutureTask в ЛЮБОМ СЛУЧАЕ после того, как он завершился: успешно, с исключением или отменой.
            // Поэтому get() уже не ожидает выполнения задачи, а сразу возвращает результат или выбрасывает исключение.
            @Override
            protected void done() {
                try {
                    result.complete(get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    result.completeExceptionally(e);
                } catch (ExecutionException e) {
                    result.completeExceptionally(e.getCause());
                } catch (CancellationException e) {
                    result.cancel(false);
                }
            }
        };

        context.register(task, result);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.execute(task);

            assertThat(taskStarted.await(2, TimeUnit.SECONDS)).isTrue();

            context.cancel();

            assertThat(task.isCancelled()).isTrue();
            assertThat(result.isCancelled()).isTrue();

            assertThat(taskInterrupted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(workerFinished.await(2, TimeUnit.SECONDS)).isTrue();


        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * checks that code
     *         for (RegisteredOperation operation : operationsToCancel) {
     *             try {
     *                 operation.cancel();
     *             } catch (RuntimeException e) {
     *                 ...
     *             }
     *         }
     *  does not throw any exception if operation.cancel() throws RuntimeException
     *  ИТОГО: ошибка отмены одной операции не должна мешать освобождению остальных ресурсов.
     */
    @Test
    void cancellationFailureDoesNotStopOtherCancellations() {
        CancellationContext context = new CancellationContext();

        AtomicInteger firstCounter = new AtomicInteger();
        AtomicInteger secondCounter = new AtomicInteger();
        AtomicInteger thirdCounter = new AtomicInteger();

        context.register(() -> {
            firstCounter.incrementAndGet();
            throw new RuntimeException("Cancellation failed");
        });

        context.register(secondCounter::incrementAndGet);
        context.register(thirdCounter::incrementAndGet);

        // NOTE: context::cancel запускает для каждой зарегистрированной операции operation.cancel();
        // operation.cancel() запускает cancelAction.run();
        // cancelAction - это переданная лямбда типа secondCounter::incrementAndGet
        assertThatCode(context::cancel)
                .doesNotThrowAnyException();

        assertThat(context.isCancelled()).isTrue();

        assertThat(firstCounter.get()).isEqualTo(1);
        assertThat(secondCounter.get()).isEqualTo(1);
        assertThat(thirdCounter.get()).isEqualTo(1);

        // Повторная отмена не должна повторно вызывать actions
        context.cancel();

        assertThat(firstCounter.get()).isEqualTo(1);
        assertThat(secondCounter.get()).isEqualTo(1);
        assertThat(thirdCounter.get()).isEqualTo(1);
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
