# Приёмы тестирования асинхронного Java-кода

Конспект приёмов, которые использовали при тестировании
`SearchPipeline`, `CancellationContext`, `FutureTask` и
`CompletableFuture`.

Главная цель --- проверять не случайное поведение потоков, а конкретные
гарантии с управляемой синхронизацией.

## 1. Управляемый CompletableFuture вместо реального ожидания

**Задача:** проверить, что pipeline правильно работает, пока внешний
источник ещё не завершился, а затем обработать его результат.

Вместо реального HTTP-вызова создаём незавершённый future:

``` java
CompletableFuture<List<Product>> catalogResult =
        new CompletableFuture<>();
```

Передаём его через тестовый `SourceCall`:

``` java
SourceCallTestImpl<List<Product>> catalogCall =
        new SourceCallTestImpl<>(catalogResult);
```

В тесте сами управляем моментом завершения:

``` java
CompletableFuture<SearchResultBox> search = pipeline.search("query");

assertThat(search.isDone()).isFalse();

catalogResult.complete(List.of(new Product("p1")));
```

**Почему это полезно:** тест точно знает, когда источник завершится. Не
нужны сеть, реальные задержки и `Thread.sleep()`.

Тот же подход позволяет моделировать ошибку и отмену:

``` java
catalogResult.completeExceptionally(new IOException("connection failed"));
catalogResult.cancel(false);
```

## 2. Mockito для изоляции зависимостей

**Задача:** проверить `SearchPipeline`, не создавая настоящий
`SearchSourceClient`.

``` java
SearchSourceClient sourceClient = mock(SearchSourceClient.class);

when(sourceClient.searchCatalog(anyString())).thenReturn(catalogCall);
when(sourceClient.searchOffers(anyString())).thenReturn(offersCall);
when(sourceClient.searchReviews(anyString())).thenReturn(reviewsCall);
```

В тесте мы контролируем все ответы источников, а сам pipeline работает с
обычным контрактом клиента.

Использовать Mockito стоит для подмены внешних зависимостей, а не для
имитации всей внутренней логики тестируемого класса.

## 3. Test double с наблюдаемым побочным эффектом

**Задача:** проверить, что отмена действительно дошла до транспорта, и
сколько раз она была вызвана.

Вместо mock-объекта используем простую реализацию `SourceCall`:

``` java
class SourceCallTestImpl<T> implements SourceCall<T> {
    private final CompletableFuture<T> result;
    private final AtomicInteger cancelTransportCounter = new AtomicInteger();

    SourceCallTestImpl(CompletableFuture<T> result) {
        this.result = result;
    }

    @Override
    public CompletableFuture<T> result() {
        return result;
    }

    @Override
    public void cancelTransport() {
        cancelTransportCounter.incrementAndGet();
    }

    @Override
    public void cancel() {
        try {
            cancelTransport();
        } finally {
            result.cancel(false);
        }
    }

    int getCancelTransportCounter() {
        return cancelTransportCounter.get();
    }
}
```

Проверка:

``` java
result.cancel(true);

assertThat(catalogCall.getCancelTransportCounter()).isEqualTo(1);
```

`AtomicInteger` нужен, потому что отмена может происходить из другого
потока или одновременно из нескольких сценариев.

**Приём:** если важен не только итоговый state, но и побочный эффект,
добавьте в test double счётчик или callback.

## 4. CountDownLatch как сигнал о событии

**Задача:** дождаться конкретного действия другого потока, не угадывая
время его выполнения.

Пример: транспортная отмена выполняется асинхронно относительно потока
теста.

``` java
CountDownLatch transportsCancelled = new CountDownLatch(3);

SourceCallTestImpl<List<Product>> catalogCall =
        new SourceCallTestImpl<>(
                new CompletableFuture<>(),
                transportsCancelled::countDown
        );
```

После отмены:

``` java
assertThat(transportsCancelled.await(1, TimeUnit.SECONDS)).isTrue();

assertThat(catalogCall.getCancelTransportCounter()).isEqualTo(1);
```

`countDown()` уменьшает счётчик latch. Когда он достигает нуля,
`await()` возвращается.

Если несколько операций должны завершить один и тот же этап, удобно
использовать общий latch с числом ожидаемых событий.

**Почему это лучше `Thread.sleep()`:**

-   `sleep()` ждёт фиксированное время, но не подтверждает, что событие
    произошло;
-   `CountDownLatch` ждёт именно событие;
-   таймаут `await()` ограничивает длительность теста и предотвращает
    бесконечное зависание.

## 5. Два latch для координации конкурентного старта

**Задача:** проверить гонку между двумя действиями, начав их максимально
близко друг к другу.

Используем: - `ready` --- каждый worker сообщает, что готов; - `start`
--- общий сигнал, разрешающий старт.

``` java
CountDownLatch ready = new CountDownLatch(2);
CountDownLatch start = new CountDownLatch(1);

Future<?> completion = racers.submit(() -> {
    ready.countDown();
    start.await();
    completeSources();
});

Future<?> cancellation = racers.submit(() -> {
    ready.countDown();
    start.await();
    result.cancel(true);
});

assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();

start.countDown();

completion.get(2, TimeUnit.SECONDS);
cancellation.get(2, TimeUnit.SECONDS);
```

Так тест не запускает вторую операцию только после полного завершения
первой.

**Важно:** конкурентный старт не гарантирует воспроизведение каждого
возможного interleaving. Такой тест полезен как regression test, но не
является математическим доказательством отсутствия всех гонок.

## 6. Проверять допустимые исходы гонки, а не конкретного победителя

**Задача:** не делать тест зависимым от того, какой поток выиграет
гонку.

Например, CPU task может завершиться одновременно с отменой. Возможны
два корректных исхода:

-   результат успел завершиться первым;
-   отмена успела первой.

Поэтому проверяем инварианты:

``` java
assertThat(task.isDone()).isTrue();
assertThat(result.isDone()).isTrue();

if (result.isCancelled()) {
    assertThat(result.isCompletedExceptionally()).isTrue();
} else {
    assertThat(result.join()).isEqualTo(42);
}
```

Не следует требовать, чтобы в такой гонке всегда побеждала отмена или
всегда побеждало успешное завершение, если контракт этого не
гарантирует.

## 7. CapturingExecutor: перехватить задачу, не запуская её

**Задача:** проверить, что pipeline передал executor правильную задачу,
а затем проверить её состояние после отмены.

Тестовый executor сохраняет переданный `Runnable`, но не запускает его:

``` java
class CapturingExecutor extends ThreadPoolExecutor {
    private final AtomicReference<Runnable> submittedTask =
            new AtomicReference<>();

    CapturingExecutor() {
        super(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    }

    @Override
    public void execute(Runnable command) {
        submittedTask.set(command);
    }

    Runnable submittedTask() {
        return submittedTask.get();
    }
}
```

Использование:

``` java
CompletableFuture<SearchResultBox> result = pipeline.search("query");

Runnable submitted = executor.submittedTask();

assertThat(submitted).isInstanceOf(FutureTask.class);

FutureTask<?> task = (FutureTask<?>) submitted;

assertThat(task.isCancelled()).isFalse();

result.cancel(true);

assertThat(task.isCancelled()).isTrue();
```

Плюс: тест детерминированно проверяет передачу и отмену задачи, не
зависит от скорости CPU.

Ограничение: такой тест **не проверяет**, что реально работающий поток
получил interrupt. Для этого нужен отдельный тест с настоящим
worker-потоком.

## 8. Проверка interrupt на реально работающем потоке

**Задача:** доказать, что `cancel(true)` действительно прерывает
выполняющуюся задачу.

Рабочий поток блокируется на latch:

``` java
CountDownLatch workerStarted = new CountDownLatch(1);
CountDownLatch workerInterrupted = new CountDownLatch(1);

Future<?> future = executor.submit(() -> {
    workerStarted.countDown();

    try {
        new CountDownLatch(1).await();
    } catch (InterruptedException e) {
        workerInterrupted.countDown();
        Thread.currentThread().interrupt();
    }
});

assertThat(workerStarted.await(2, TimeUnit.SECONDS)).isTrue();

assertThat(future.cancel(true)).isTrue();

assertThat(workerInterrupted.await(2, TimeUnit.SECONDS)).isTrue();
```

Здесь `workerStarted` гарантирует, что задача уже вошла в блокирующий
участок до отмены.

`workerInterrupted` подтверждает, что worker получил interrupt и
`await()` выбросил `InterruptedException`.

В `finally` нужно освобождать latch и останавливать executor, чтобы тест
не оставлял рабочие потоки:

``` java
finally {
    executor.shutdownNow();
    assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
}
```

## 9. Сравнение FutureTask и CompletableFuture cancellation

**Задача:** проверить принципиальную разницу: `FutureTask.cancel(true)`
способен прервать worker, а `CompletableFuture.cancel(true)` сам по себе
этого не гарантирует.

Для эксперимента запускаем `CompletableFuture` через `runAsync`, ждём
старта worker, отменяем future, проверяем, что worker не завершился от
interrupt, затем освобождаем его через latch.

``` java
CountDownLatch workerStarted = new CountDownLatch(1);
CountDownLatch workerInterrupted = new CountDownLatch(1);
CountDownLatch releaseWorker = new CountDownLatch(1);
CountDownLatch workerCompleted = new CountDownLatch(1);

CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
    workerStarted.countDown();

    try {
        releaseWorker.await();
    } catch (InterruptedException e) {
        workerInterrupted.countDown();
        Thread.currentThread().interrupt();
    } finally {
        workerCompleted.countDown();
    }
}, executor);

assertThat(workerStarted.await(2, TimeUnit.SECONDS)).isTrue();

assertThat(future.cancel(true)).isTrue();

assertThat(workerInterrupted.await(200, TimeUnit.MILLISECONDS)).isFalse();
assertThat(workerCompleted.getCount()).isEqualTo(1);

releaseWorker.countDown();

assertThat(workerCompleted.await(2, TimeUnit.SECONDS)).isTrue();
```

Окно в 200 мс здесь --- ограниченное окно наблюдения в контролируемом
эксперименте, а не абсолютное доказательство отсутствия interrupt во
всех возможных реализациях и условиях.

## 10. Проверка deadline через ограниченное ожидание

**Задача:** проверить, что запрос завершается по deadline и не
заставлять тест ждать бесконечно.

Для `CompletableFuture` можно использовать:

``` java
assertThatThrownBy(result::join)
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(TimeoutException.class);
```

Или ограниченное ожидание:

``` java
assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(TimeoutException.class);
```

Разница в обёртке зависит от метода: - `join()` оборачивает обычную
ошибку в `CompletionException`; - `get()` сообщает об ошибке вычисления
через `ExecutionException`; - `get(timeout, unit)` может дополнительно
выбросить `TimeoutException`, если сам вызов `get` не дождался
завершения за указанный интервал.

В тесте важно различать timeout самого pipeline и timeout ожидания в
тесте.

## 11. Проверка идемпотентности через счётчик вызовов

**Задача:** убедиться, что повторная отмена не выполняет side effect
повторно.

``` java
AtomicInteger cancelCounter = new AtomicInteger();

context.register(cancelCounter::incrementAndGet);

context.cancel();
context.cancel();
context.cancel();

assertThat(cancelCounter.get()).isEqualTo(1);
```

Проверяется не только финальное состояние, но и количество вызовов
операции.

Этот приём подходит для: - идемпотентности `cancel()`; - однократной
отмены транспорта; - отсутствия повторного запуска callback; - проверки
retry/cleanup действий.

## 12. Проверка устойчивости при исключении одного callback

**Задача:** убедиться, что ошибка одной операции отмены не мешает
отменить остальные.

``` java
AtomicInteger first = new AtomicInteger();
AtomicInteger second = new AtomicInteger();

context.register(() -> {
    first.incrementAndGet();
    throw new RuntimeException("cancel failed");
});

context.register(second::incrementAndGet);

assertThatCode(context::cancel).doesNotThrowAnyException();

assertThat(first.get()).isEqualTo(1);
assertThat(second.get()).isEqualTo(1);
```

Первый callback увеличивает счётчик, а затем бросает исключение.
`CancellationContext` перехватывает его и продолжает обход
зарегистрированных операций.

## 13. Инъекция функций вместо сложных наследников и реальных алгоритмов

**Задача:** тестировать orchestration pipeline независимо от деталей
filtering/ranking.

Тестовый pipeline получает функции:

``` java
new TestSearchPipeline(
        sourceClient,
        REQUEST_TIMEOUT,
        products -> products,
        products -> products
);
```

Так можно передать поведение, нужное конкретному тесту:

``` java
products -> {
    throw new IllegalStateException("filter failed");
}
```

Или контролировать момент завершения обработки через latch.

Это позволяет тестировать: - порядок этапов; - переходы между
стадиями; - обработку исключений; - deadline; - отмену CPU-задачи.

При этом не нужно запускать настоящие алгоритмы фильтрации и
ранжирования.

## 14. Проверка завершённого объекта и отсутствия побочных эффектов

**Задача:** проверить, что отмена уже завершённого поиска не меняет
результат и не отменяет транспорт задним числом.

``` java
SearchResultBox completedResult = result.join();

assertThat(result.isDone()).isTrue();
assertThat(result.isCompletedExceptionally()).isFalse();
assertThat(result.isCancelled()).isFalse();

assertThat(result.cancel(true)).isFalse();

assertThat(result.join()).isSameAs(completedResult);
assertThat(catalogCall.getCancelTransportCounter()).isZero();
```

Сначала `join()` дожидается завершения всего pipeline. Только затем
вызывается `cancel()`.

Так тест проверяет именно поведение после завершения, а не гонку между
завершением и отменой.

## 15. Cleanup тестовых потоков и executor

Асинхронные тесты должны освобождать ресурсы даже при падении assertion.

Используйте `try/finally` или `try-with-resources`:

``` java
ExecutorService executor = Executors.newFixedThreadPool(2);

try {
    // test
} finally {
    executor.shutdownNow();
    assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
}
```

Для pipeline, реализующего `AutoCloseable`:

``` java
try (TestSearchPipeline pipeline = new TestSearchPipeline(...)) {
    // test
}
```

Иначе зависший worker может удерживать JVM или влиять на следующие
тесты.

## 16. Практические правила для асинхронных тестов

1.  Предпочитать управляемые `CompletableFuture` реальным сетевым
    вызовам.
2.  Не использовать `Thread.sleep()` как способ дождаться события.
3.  Для синхронизации потоков использовать `CountDownLatch`,
    `CyclicBarrier`, `Phaser` или другие подходящие concurrency
    primitives.
4.  Всегда задавать ограниченное время для `await()`, `get()` и
    `awaitTermination()`.
5.  Для гонок синхронизировать готовность участников и общий старт.
6.  Не требовать конкретного победителя гонки, если контракт допускает
    несколько исходов.
7.  Проверять и итоговое состояние, и побочные эффекты (например, число
    вызовов отмены).
8.  Разделять тесты, которые проверяют состояние `FutureTask`, и тесты,
    которые проверяют реальный interrupt worker.
9.  Использовать test double с наблюдаемыми счётчиками/callback, если
    важна реакция внешнего ресурса.
10. Всегда освобождать latch и завершать executor в `finally`.
11. Не путать timeout pipeline с timeout ожидания результата в самом
    тесте.
12. Тестировать не только успешные сценарии, но и исключения, отмену,
    повторные вызовы, позднюю регистрацию и конкурентные переходы.

## 17. Что дают эти приёмы в совокупности

В тестах `meta-search-runtime` мы проверяем несколько разных уровней:

-   **Модель состояния:** `isDone`, `isCancelled`,
    `isCompletedExceptionally`.
-   **Сигналы управления:** `cancel`, `interrupt`, `complete`,
    `completeExceptionally`.
-   **Оркестрацию:** какие операции запускаются и в каком порядке.
-   **Внешние side effects:** отмена транспорта и число её вызовов.
-   **Гонки:** конкурентное завершение и отмена.
-   **Временные ограничения:** source timeout и общий request deadline.
-   **Жизненный цикл:** cleanup регистраций, worker threads и executor.

Ключевой принцип: хороший тест асинхронного кода должен управлять
моментами событий и проверять контракт, а не надеяться на удачное
расписание потоков.
