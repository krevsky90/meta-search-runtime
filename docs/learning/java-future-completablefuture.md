# Java Future и CompletableFuture: модель состояний и композиция

Конспект по `Future`, `FutureTask` и `CompletableFuture`, с привязкой к
реализации `SearchPipeline`.

## 1. Общая картина

В Java есть несколько связанных, но не взаимозаменяемых абстракций:

  -----------------------------------------------------------------------
  Тип                                 Основная роль
  ----------------------------------- -----------------------------------
  `Runnable`                          Представляет действие без
                                      возвращаемого результата

  `Callable<V>`                       Представляет действие, возвращающее
                                      `V` и способное выбросить checked
                                      exception

  `Future<V>`                         Представляет результат асинхронной
                                      операции, который можно дождаться,
                                      получить или попытаться отменить

  `FutureTask<V>`                     Реализация `RunnableFuture<V>`:
                                      одновременно задача, которую можно
                                      выполнить, и `Future` для
                                      наблюдения за результатом

  `CompletableFuture<V>`              Реализация `Future<V>` и
                                      `CompletionStage<V>`: результат
                                      можно завершить вручную и строить
                                      цепочки зависимых стадий
  -----------------------------------------------------------------------

Упрощённая связь:

``` text
Runnable                  Callable<V>
   |                           |
   |                           |
   +------ FutureTask<V> -------+
                 |
                 +-- implements RunnableFuture<V>
                         |
                         +-- extends Runnable
                         +-- extends Future<V>

CompletableFuture<V>
   |
   +-- implements Future<V>
   +-- implements CompletionStage<V>
```

`Future` сам по себе не запускает задачу. Это контракт для получения
результата и управления ожиданием/отменой. Запуск обычно организует
`Executor` или `ExecutorService`.

`CompletableFuture` может существовать и без отдельной задачи: например,
его можно завершить вручную через `complete()`.

## 2. Runnable и Callable

### Runnable

`Runnable` описывает действие без результата:

``` java
Runnable task = () -> System.out.println("hello");
```

Метод:

``` java
void run();
```

У `Runnable.run()` нет возвращаемого значения и нет объявления checked
exceptions.

### Callable`<V>`{=html}

`Callable<V>` описывает действие с результатом:

``` java
Callable<Integer> task = () -> 42;
```

Метод:

``` java
V call() throws Exception;
```

`Callable` может вернуть значение и выбросить checked exception.

### Как они связаны с ExecutorService

``` java
executor.execute(runnable);       // запустить Runnable, Future не возвращается
Future<?> f1 = executor.submit(runnable);

Future<Integer> f2 = executor.submit(callable);
```

`submit()` возвращает `Future`, через который можно получить результат,
проверить завершение или отменить задачу.

## 3. Future: контракт результата

Основные методы:

``` java
boolean cancel(boolean mayInterruptIfRunning);
boolean isCancelled();
boolean isDone();
V get() throws InterruptedException, ExecutionException;
V get(long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException;
```

### Когда `isDone() == true`

`isDone()` возвращает `true`, когда операция находится в любом
терминальном состоянии:

1.  Успешно завершилась.
2.  Завершилась с исключением.
3.  Была отменена.

Следовательно, `isDone()` **не означает успешное завершение**.

Типичный разбор:

``` java
if (!future.isDone()) {
    // операция ещё не завершена
} else if (future.isCancelled()) {
    // отменена
} else {
    // завершена, но для проверки успеха нужно получить результат
}
```

Для произвольного `Future` нет универсального метода
`isCompletedExceptionally()`. Такой метод есть у `CompletableFuture`.

### Что означает cancel()

`cancel(true)` просит отменить задачу и, если она уже выполняется,
попытаться прервать поток через interrupt. Это не гарантирует остановку
кода: задача может игнорировать interrupt.

`cancel(false)` просит отменить задачу, не прерывая выполняющийся поток.

Успешная отмена обычно приводит к:

``` java
future.isCancelled() == true
future.isDone() == true
```

Если задача уже завершилась, `cancel()` обычно возвращает `false`.

`Future` --- интерфейс. Фактическое поведение отмены зависит от
реализации. Особенно важно различать `FutureTask` и `CompletableFuture`.

## 4. FutureTask: задача и Future одновременно

`FutureTask<V>` реализует `RunnableFuture<V>`. Его можно передать
executor как `Runnable`, а результат наблюдать как `Future`.

``` java
FutureTask<Integer> task = new FutureTask<>(() -> {
    return 42;
});

executor.execute(task);

Integer result = task.get();
```

У `FutureTask` есть внутренние состояния реализации:

-   `NEW` --- ещё не завершена;
-   `COMPLETING` --- переходное состояние при фиксации результата;
-   `NORMAL` --- успешное завершение;
-   `EXCEPTIONAL` --- завершение с исключением;
-   `CANCELLED` --- отменена;
-   `INTERRUPTING` --- переходное состояние при отмене с interrupt;
-   `INTERRUPTED` --- отмена с отправленным interrupt.

Это детали реализации `FutureTask`, а не публичный enum API.

`FutureTask.isDone()` возвращает `true`, когда состояние перестало быть
`NEW`. То есть при успешном завершении, исключении и отмене.

### Важная особенность cancel(true)

`FutureTask.cancel(true)` умеет прервать поток, выполняющий `run()`
этого `FutureTask`, если задача уже запущена.

Это свойство мы использовали для CPU processing в `SearchPipeline`.

## 5. CompletableFuture: результат и граф зависимостей

`CompletableFuture<V>` реализует `Future<V>` и `CompletionStage<V>`.

Он умеет:

-   хранить результат;
-   завершаться вручную;
-   завершаться от результата другой стадии;
-   строить цепочки зависимых стадий;
-   объединять несколько стадий;
-   обрабатывать исключения.

### Три основных исхода

С точки зрения использования у `CompletableFuture` есть три основных
исхода:

1.  Нормальное завершение со значением, включая `null` для обычного
    `CompletableFuture`.
2.  Исключительное завершение.
3.  Отмена.

Отмена в `CompletableFuture` представлена как особый вариант exceptional
completion с `CancellationException`.

Поэтому:

``` java
future.cancel(false);

future.isDone();                 // true
future.isCancelled();            // true
future.isCompletedExceptionally(); // true
```

`isCompletedExceptionally()` возвращает `true` и для обычного
exceptional completion, и для cancellation.

### Как завершить CompletableFuture

``` java
CompletableFuture<String> future = new CompletableFuture<>();

future.complete("OK");                  // нормальное завершение
future.completeExceptionally(error);   // исключительное завершение
future.cancel(false);                  // отмена
```

Повторное завершение не меняет уже зафиксированный результат: методы
`complete`/`completeExceptionally` возвращают `false`, если future уже
завершён.

Фабричные методы:

``` java
CompletableFuture.completedFuture("OK");
CompletableFuture.failedFuture(new IOException());
```

### get() и join()

Оба метода ждут завершения, но по-разному сообщают об ошибке:

-   `get()` использует checked `ExecutionException` для ошибки
    вычисления и требует обработать `InterruptedException`.
-   `join()` выбрасывает unchecked `CompletionException` для обычного
    exceptional completion.
-   При отмене оба могут выбросить `CancellationException` напрямую.

## 6. Композиция CompletableFuture

Методы композиции создают зависимые стадии. Обычно возвращается **новый
`CompletableFuture`**, представляющий результат следующего шага.

### thenApply --- преобразовать значение

``` java
CompletableFuture<Integer> length =
        source.thenApply(String::length);
```

Тип: `T -> U`, результат --- `CompletableFuture<U>`.

### thenAccept --- использовать значение без результата

``` java
CompletableFuture<Void> logged =
        source.thenAccept(System.out::println);
```

Тип: `T -> void`, результат --- `CompletableFuture<Void>`.

### thenRun --- выполнить действие без доступа к результату

``` java
CompletableFuture<Void> next =
        source.thenRun(() -> audit());
```

### thenCompose --- связать асинхронные операции

Если функция возвращает `CompletableFuture<U>`, `thenCompose` не создаёт
вложенный future:

``` java
CompletableFuture<User> user =
        userId.thenCompose(id -> loadUserAsync(id));
```

В отличие от `thenApply` с такой функцией, результат не будет
`CompletableFuture<CompletableFuture<User>>`.

### handle --- обработать и значение, и ошибку

``` java
CompletableFuture<Result> normalized =
        source.handle((value, error) -> {
            if (error != null) {
                return fallback();
            }
            return convert(value);
        });
```

`handle` запускается и при нормальном, и при исключительном завершении.
Если callback возвращает значение, downstream future завершается
нормально; если callback выбрасывает исключение --- исключительно.

### whenComplete --- наблюдать завершение

``` java
CompletableFuture<Result> observed =
        source.whenComplete((value, error) -> log(value, error));
```

`whenComplete` создаёт **новый dependent CompletableFuture**. Callback
получает результат/ошибку, но не предназначен для преобразования
значения.

Если callback сам выбросит исключение, dependent stage может завершиться
этим исключением. Если upstream уже завершился исключительно, исходная
ошибка обычно сохраняется.

### exceptionally --- восстановиться после ошибки

``` java
CompletableFuture<Result> recovered =
        source.exceptionally(error -> fallback());
```

Callback получает ошибку и возвращает значение для нормального
завершения downstream stage.

### Объединение стадий

``` java
CompletableFuture<A> a = ...;
CompletableFuture<B> b = ...;

CompletableFuture<C> combined =
        a.thenCombine(b, this::combine);

CompletableFuture<Void> all =
        CompletableFuture.allOf(a, b);

CompletableFuture<Object> any =
        CompletableFuture.anyOf(a, b);
```

`allOf` завершается, когда завершились все переданные стадии. Он не
собирает их значения: значения получают отдельно через `join()` после
завершения `allOf`.

### Какие методы возвращают новый CompletableFuture

Практическое правило: методы `CompletionStage`, строящие следующую
стадию, возвращают новый dependent stage. Среди часто используемых:

-   `thenApply`, `thenApplyAsync`;
-   `thenAccept`, `thenAcceptAsync`;
-   `thenRun`, `thenRunAsync`;
-   `thenCompose`, `thenComposeAsync`;
-   `handle`, `handleAsync`;
-   `whenComplete`, `whenCompleteAsync`;
-   `exceptionally` и async-варианты;
-   `thenCombine`, `thenAcceptBoth`, `runAfterBoth`;
-   `applyToEither`, `acceptEither`, `runAfterEither`;
-   `allOf`, `anyOf` --- статические фабрики объединяющих future.

### Методы, которые не создают новую зависимую стадию

Есть важные методы, которые возвращают тот же объект и меняют его
состояние/поведение:

``` java
future.orTimeout(500, TimeUnit.MILLISECONDS);
future.completeOnTimeout(fallback, 500, TimeUnit.MILLISECONDS);
future.completeAsync(supplier, executor);
```

В частности, `orTimeout()` **не создаёт новый future**, а устанавливает
timeout на тот объект, на котором вызван.

Это важно для понимания следующего кода:

``` java
CompletableFuture<List<Product>> timedResult =
        call.result()
                .thenApply(Function.identity())
                .orTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS);
```

`thenApply(Function.identity())` создаёт отдельный dependent future.
Затем `orTimeout()` применяется к нему, а не к исходному
`call.result()`.

Так timeout одного слоя не завершает timeout-ом исходный future внешнего
клиента.

## 7. Правила распространения завершения и отмены

Для цепочки:

``` java
CompletableFuture<A> source = ...;
CompletableFuture<B> stage1 = source.thenApply(this::convert);
CompletableFuture<C> stage2 = stage1.thenApply(this::enrich);
```

важно помнить:

1.  Завершение upstream future не зависит от downstream future. Отмена
    `stage1` или `stage2` сама по себе не отменяет `source`.
2.  Исключительное завершение распространяется вниз по цепочке: если
    `stage1` завершается исключением, `stage2` также завершится
    исключительно, если не восстановит ошибку.
3.  Отмена и обычное исключительное завершение --- не одно и то же.
    Отмена имеет отдельную семантику и распознаётся через
    `isCancelled()`.
4.  Отмена dependent `CompletableFuture` сама по себе не является
    механизмом отмены исходной физической задачи.
5.  `CompletableFuture.cancel(true)` не гарантирует interrupt рабочего
    потока. Параметр `mayInterruptIfRunning` для `CompletableFuture` не
    обеспечивает прерывание вычисления, которое породило его результат.

Последний пункт --- одна из причин, по которой в проекте CPU-задачи
выполняются через `FutureTask`, а не через
`CompletableFuture.supplyAsync()`.

## 8. Как эти сущности используются в meta-search-runtime

### 8.1. Внешние источники: SourceCall и CompletableFuture

`SearchSourceClient` запускает асинхронные обращения к Catalog, Offers и
Reviews и возвращает `SourceCall<T>`.

Упрощённо:

``` java
public interface SourceCall<T> {
    CompletableFuture<T> result();
    void cancelTransport();
    void cancel();
}
```

`result()` сообщает о результате асинхронного обращения.

`cancelTransport()` должен остановить физический запрос средствами
конкретного HTTP-клиента.

`cancel()` объединяет отмену транспорта и логического future результата.

Это разделение необходимо, потому что отмена `CompletableFuture` сама по
себе не означает, что сетевой запрос остановлен.

### 8.2. Нормализация источников через композицию

В `searchGeneric()`:

``` java
CompletableFuture<List<T>> timedResult = call.result()
        .thenApply(Function.identity())
        .orTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS);

CompletableFuture<SearchBox<List<T>>> resultCf =
        timedResult.handle((result, ex) -> {
            Throwable cause = unwrap(ex);

            if (cause != null) {
                return new SearchBox<>(List.of(), toSourceError(source, cause));
            }

            return new SearchBox<>(List.copyOf(result), null);
        });
```

Здесь:

-   `thenApply(identity)` создаёт отдельную dependent stage;
-   `orTimeout` устанавливает timeout на эту stage;
-   `handle` превращает успешный/ошибочный исход источника в единый
    `SearchBox`;
-   downstream pipeline получает нормализованный результат, а не
    исключение каждого отдельного источника.

В реальном коде дополнительно обрабатываются `null`, некорректные
ответы, локальная отмена транспорта и снятие регистрации.

### 8.3. CPU processing через FutureTask + CompletableFuture

В `processingGeneric()`:

``` java
CompletableFuture<List<T>> cf = new CompletableFuture<>();

FutureTask<List<T>> ft = new FutureTask<>(
        () -> processingFunction.apply(input)
) {
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

cpuExecutor.execute(ft);
```

Здесь две сущности выполняют разные роли:

-   `FutureTask` представляет реально исполняемую CPU-задачу и позволяет
    вызвать `cancel(true)`;
-   `CompletableFuture` представляет результат для композиции pipeline.

`FutureTask.done()` вызывается при нормальном завершении, исключении или
отмене. Он переносит итог задачи в `CompletableFuture`.

Так мы получаем и возможность прервать CPU worker, и удобную композицию
через `CompletionStage`.

### 8.4. Последовательность filtering -\> ranking

В `search()`:

``` java
CompletableFuture<ProcessingBox<List<Product>>> rankedProducts =
        filteredProducts.thenCompose(filterBox -> {
            if (!filterBox.isSuccessful()) {
                return CompletableFuture.completedFuture(filterBox);
            }

            return processingGeneric(
                    this::rankProducts,
                    filterBox.data(),
                    ...
            );
        });
```

`thenCompose` нужен потому, что `processingGeneric()` уже возвращает
`CompletableFuture`.

Он связывает асинхронные этапы в одну цепочку и не создаёт вложенный
future.

### 8.5. Параллельное ожидание источников

``` java
CompletableFuture<SearchResultBox> result =
        CompletableFuture.allOf(products, offers, reviews)
                .thenCompose(unused -> {
                    SearchBox<List<Product>> productBox = products.join();
                    SearchBox<List<Offer>> offerBox = offers.join();
                    SearchBox<List<Review>> reviewBox = reviews.join();

                    // дальнейшая обработка
                });
```

`allOf` ожидает завершения всех нормализованных source futures. После
его нормального завершения `join()` не блокирует поток, поскольку эти
futures уже завершены.

### 8.6. Общий deadline и отмена запроса

``` java
CompletableFuture<SearchResultBox> result = pipeline
        .orTimeout(remainingNanos, TimeUnit.NANOSECONDS);

result.whenComplete((value, error) -> {
    Throwable cause = unwrap(error);

    if (cause instanceof TimeoutException
            || cause instanceof CancellationException) {
        cancellationContext.cancel();
    }
});

return result;
```

Важный нюанс: `whenComplete()` создаёт новый dependent future. Поэтому в
проекте callback регистрируется как side effect, а наружу возвращается
именно `result`.

Если вернуть future, созданный `whenComplete`, отмена объекта, который
видит вызывающий код, не будет автоматически означать отмену исходного
`result`.

### 8.7. CancellationContext связывает физические операции

`CancellationContext` хранит зарегистрированные действия отмены:

-   `SourceCall` внешнего источника;
-   `FutureTask` CPU processing и связанный с ним `CompletableFuture`.

При отмене запроса контекст вызывает действия отмены всех ещё
зарегистрированных операций.

Таким образом:

-   `CompletableFuture` обеспечивает композицию и распространение
    результата;
-   `FutureTask` обеспечивает управляемую отмену CPU-задачи с interrupt;
-   `SourceCall` обеспечивает отмену внешнего транспорта;
-   `CancellationContext` координирует отмену всего запроса.

## 9. Короткая памятка

-   `Runnable` --- действие без результата.
-   `Callable<V>` --- действие с результатом.
-   `Future<V>` --- handle на результат асинхронной операции.
-   `FutureTask<V>` --- исполняемая задача + `Future`; `cancel(true)`
    может прервать worker.
-   `CompletableFuture<V>` --- результат + композиция стадий;
    `cancel(true)` не является надёжным способом прервать породившую его
    задачу.
-   `isDone()` --- завершено любым способом, не обязательно успешно.
-   `isCancelled()` --- завершено именно отменой.
-   `isCompletedExceptionally()` у `CompletableFuture` включает и
    отмену.
-   `thenApply` преобразует значение.
-   `thenCompose` связывает асинхронные этапы.
-   `handle` обрабатывает и результат, и ошибку.
-   `whenComplete` наблюдает завершение, но создаёт новый dependent
    future.
-   `orTimeout` меняет сам объект, на котором вызван.
-   Отмена downstream future не отменяет автоматически upstream future
    или физическую задачу.
