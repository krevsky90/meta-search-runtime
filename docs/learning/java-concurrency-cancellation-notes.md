# 1. Основные типы и их отношения

## 1.1. `Runnable`

- Представляет задачу, которую нужно выполнить.
- Не возвращает результат.
- Не объявляет checked exceptions в сигнатуре `run()`.

```java
Runnable task = () -> System.out.println("Hello");
```

## 1.2. `Callable<T>`

- Представляет задачу, которая возвращает результат типа `T`.
- Может выбрасывать checked exceptions.

```java
Callable<Integer> task = () -> {
    Thread.sleep(100);
    return 42;
};
```

## 1.3. `Future<T>`

`Future` — это интерфейс для управления результатом асинхронной операции.

Основные методы:

| Метод | Назначение |
|---|---|
| `get()` | Получить результат, при необходимости блокируя поток |
| `get(timeout, unit)` | Получить результат с ограничением времени ожидания |
| `cancel(boolean)` | Попытаться отменить выполнение |
| `isCancelled()` | Проверить, была ли операция отменена |
| `isDone()` | Проверить, завершилась ли операция |

Важное различие: `Future` — это не сама задача и не поток, а объект, через который можно взаимодействовать с результатом выполнения задачи.

## 1.4. `FutureTask<T>`

`FutureTask` — конкретная реализация интерфейса `RunnableFuture<T>`, который одновременно наследует `Runnable` и `Future<T>`.

Это означает, что один объект:

- можно передать исполнителю на выполнение;
- можно отменить;
- можно получить из него результат;
- можно проверить его состояние.

```java
FutureTask<Integer> task = new FutureTask<>(() -> 42);

executor.execute(task);

Integer result = task.get();
```

Важный момент: `FutureTask` сам по себе не запускает отдельный поток. Он лишь представляет задачу. Её должен выполнить кто-то другой — например, `Executor`.

## 1.5. `CompletableFuture<T>`

`CompletableFuture` — реализация интерфейса `Future<T>`, которая дополнительно позволяет:

- вручную завершать вычисление;
- регистрировать callbacks;
- строить цепочки зависимых операций;
- комбинировать результаты нескольких операций;
- обрабатывать исключения без блокирующего `get()`.

```java
CompletableFuture<Integer> cf = new CompletableFuture<>();

cf.complete(42);

Integer result = cf.join();
```

В отличие от обычного `Future`, `CompletableFuture` ориентирован не только на получение результата, но и на композицию асинхронных вычислений.

---

# 2. Как задачи связываются с Executor

## 2.1. `Executor.execute()`

Метод принимает `Runnable` и передаёт его исполнителю.

```java
FutureTask<Integer> task = new FutureTask<>(() -> 42);

executor.execute(task);
```

Мы самостоятельно создаём `FutureTask`, поэтому можем сохранить ссылку именно на тот объект, который передали в Executor.

Это важно для последующей отмены.

## 2.2. `ExecutorService.submit()`

Метод `submit()` самостоятельно создаёт внутреннюю обёртку над переданной задачей и возвращает `Future`.

```java
Future<Integer> future = executor.submit(() -> 42);
```

Здесь мы не управляем напрямую внутренним объектом задачи, но получаем `Future`, через который можем отменить её выполнение.

В частности, `submit()` обычно создаёт `FutureTask` или эквивалентную реализацию `RunnableFuture`.

**Практическое различие:**

- `execute()` — передаём задачу сами, отдельно управляя её объектом.
- `submit()` — передаём задачу и получаем созданный исполнителем `Future`.

---

# 3. Что происходит при отмене

Это самая важная часть наших экспериментов.

## 3.1. `Future.cancel(true)` может прервать выполняющуюся задачу

Если задача выполняется через `ExecutorService.submit()`, то:

```java
future.cancel(true);
```

может послать interrupt потоку, выполняющему задачу.

Но это не означает, что задача гарантированно остановится.

Она должна реагировать на прерывание.

Например, блокирующие операции могут выбросить `InterruptedException`:

```java
Thread.sleep(1000);
latch.await();
blockingQueue.take();
```

Для CPU-bound задачи необходимо самостоятельно периодически проверять флаг прерывания:

```java
while (hasMoreWork()) {
    if (Thread.currentThread().isInterrupted()) {
        return;
    }

    processNextItem();
}
```

Важно: interrupt — это механизм кооперативного прерывания, а не принудительное уничтожение потока.

## 3.2. `CompletableFuture.cancel(true)` не прерывает выполняющуюся задачу `supplyAsync()`

Мы проверили это экспериментально.

```java
CompletableFuture<Void> cf = CompletableFuture.runAsync(
    () -> {
        // Долгая работа
    },
    executor
);

cf.cancel(true);
```

Результат:

- `CompletableFuture` переходит в состояние отмены;
- `cf.isCancelled()` возвращает `true`;
- `cf.isDone()` возвращает `true`;
- выполняющийся worker не получает interrupt;
- задача может продолжать работать.

**Вывод:** нельзя использовать `CompletableFuture.cancel(true)` как механизм остановки уже выполняющейся CPU-задачи.

## 3.3. `FutureTask.cancel(true)` и `CompletableFuture.cancel(true)` — разные механизмы

| Свойство | `FutureTask` | `CompletableFuture` |
|---|---|---|
| Реализует `Future` | Да | Да |
| Может отменить своё выполнение | Да | Отменяет собственное completion-состояние |
| Может послать interrupt worker-потоку | Да, при `cancel(true)` | Нет |
| Может продолжать выполнение после отмены | Если задача не реагирует на interrupt | Да, если underlying task продолжает работать |

Это ключевое различие, которое и привело нас к паре `FutureTask + CompletableFuture`.

### Сравнение `submit()` и `supplyAsync()`

| Возможность | `submit()` → `Future` | `supplyAsync()` → `CompletableFuture` |
|---|:---:|:---:|
| `cancel(true)` меняет состояние Future | Да | Да |
| Пытается прервать выполняющийся поток | Да | Нет |
| Требует реакции задачи на interrupt | Да | Не применимо к отмене `CompletableFuture` |
| Можно получить результат через `get()` | Да | Да |
| Удобная композиция через `thenApply()` | Нет | Да |


## 3.4. Отмена в pipeline: две независимые проблемы

Для `pipeline.cancel(...)` нужно учитывать две независимые проблемы:

1. **Нет автоматического распространения отмены между Future в цепочке.** Отмена одного stage сама по себе не отменяет upstream- и вложенные Future.
2. **Отмена `CompletableFuture` не гарантирует отмену выполняющейся задачи в Executor.** Future может перейти в cancelled state, в то время как worker продолжит работу.

## 3.4. Отмена не означает, что вычисление физически остановлено

Нужно различать два понятия:

1. **Логическая отмена:** результат больше не нужен, future переходит в cancelled state.
2. **Физическая остановка:** выполняющаяся задача действительно прекратила работу.

Первое не гарантирует второго.

Например, `CompletableFuture` может быть отменён, а worker продолжит потреблять CPU.

---

# 4. Композиция CompletableFuture и распространение отмены

Мы провели несколько экспериментов с `thenApply()` и `thenCompose()`.

## 4.1. `thenApply()` создаёт зависимый future

```java
CompletableFuture<String> source = new CompletableFuture<>();

CompletableFuture<Integer> stage1 =
    source.thenApply(String::length);

CompletableFuture<String> stage2 =
    stage1.thenApply(length -> "Length: " + length);
```

Получается цепочка:

```text
source
   |
   v
stage1
   |
   v
stage2
```

Но эта цепочка описывает распространение результата и исключений, а не управление жизненным циклом всех операций.

## 4.2. Отмена upstream распространяется downstream

Если отменить `source`:

```java
source.cancel(true);
```

то:

- `source.isCancelled()` → `true`;
- `stage1.isDone()` → `true`;
- `stage2.isDone()` → `true`;
- `stage1.isCompletedExceptionally()` → `true`;
- `stage2.isCompletedExceptionally()` → `true`;
- `stage1.isCancelled()` и `stage2.isCancelled()` → `false`.

Зависимые stages завершаются исключением вследствие отмены upstream.

Они не обязательно сами переходят в состояние `cancelled`.

## 4.3. Отмена downstream не распространяется upstream

Если отменить `stage2`:

```java
stage2.cancel(true);
```

то:

- `stage2` отменён;
- `stage1` продолжает существовать;
- `source` продолжает существовать.

То есть отмена зависимого future не отменяет его предшественников.

**Правило:** `CompletableFuture` не реализует автоматическую двустороннюю отмену всей цепочки.

## 4.4. `thenCompose()` также не обеспечивает двустороннюю отмену

```java
CompletableFuture<String> source = new CompletableFuture<>();
CompletableFuture<String> inner = new CompletableFuture<>();

CompletableFuture<String> pipeline =
    source.thenCompose(value -> inner);
```

`thenCompose()` объединяет результат внутреннего future с внешним.

Правила отмены:

1. Отмена результирующего Future (`pipeline`) не отменяет внутренние операции (`source`, `inner`).
2. Отмена внутреннего Future (`inner`) передаётся результирующему Future (`pipeline`), если `pipeline` уже начал отслеживать внутренний Future. При этом `pipeline` завершается исключением вследствие отмены, но `isCancelled()` у него будет `false`.

Если `inner` отменён до завершения `source`, `pipeline` может оставаться незавершённым, пока `source` не завершится. Когда `source` завершится и callback вернёт отменённый `inner`, `pipeline` завершится исключением из-за отмены.

Иными словами, `thenCompose()` отвечает за композицию результатов, а не за управление жизненным циклом вложенных операций.

## 4.4.1. Общие правила распространения завершения по цепочке

1. **Завершение upstream Future не зависит от downstream Future.** Отмена или завершение `stage1` не завершает `source`. Аналогично, отмена или завершение downstream stage не изменяет состояние его upstream stages.
2. **Исключительное завершение распространяется вниз по цепочке.** Если `stage1` завершился исключением, `stage2`, зависящий от него, тоже завершится исключительно.
3. **Отмена и исключительное завершение — не одно и то же.** Отмена непосредственно отменённого Future устанавливает `isCancelled() == true`. У зависимых Future, завершившихся вследствие этой отмены, `isCancelled() == false`, хотя `isCompletedExceptionally() == true`.

## 4.5. `isDone()`, `isCompletedExceptionally()` и `isCancelled()`

Эти методы позволяют проверить разные аспекты состояния `CompletableFuture`:

- `isDone()` — future завершён (успешно, с исключением или отменён).
- `isCompletedExceptionally()` — future завершён неуспешно, включая отмену.
- `isCancelled()` — future завершён именно в результате отмены.

| Сценарий | `isDone()` | `isCompletedExceptionally()` | `isCancelled()` |
|---|:---:|:---:|:---:|
| Future ожидает результата | `false` | `false` | `false` |
| `complete(value)` | `true` | `false` | `false` |
| `completeExceptionally(ex)` | `true` | `true` | `false` |
| `cancel(true)` | `true` | `true` | `true` |
| `supplyAsync()` успешно завершился | `true` | `false` | `false` |
| `supplyAsync()` выбросил исключение | `true` | `true` | `false` |
| `orTimeout()` сработал | `true` | `true` | `false` |
| `completeOnTimeout()` сработал | `true` | `false` | `false` |
| Зависимый Future получил исключение от upstream | `true` | `true` | `false` |

Есть и тонкость: если вызвать `completeExceptionally(new CancellationException())`, `CompletableFuture` также считает себя отменённым. А если передать `CompletionException`, оборачивающий `CancellationException`, результат может отличаться.

Поэтому при обработке исключений важно учитывать их фактическую структуру.

## 4.6. `whenComplete()` создаёт новый dependent future

`whenComplete()` не изменяет исходный `CompletableFuture`. Он создаёт и возвращает новый зависимый `CompletableFuture`.

```java
CompletableFuture<Integer> source = CompletableFuture.completedFuture(42);

CompletableFuture<Integer> dependent = source.whenComplete((value, ex) -> {
    System.out.println("value = " + value);
});
```

Здесь:

- `source` — исходный future;
- `dependent` — новый future, возвращённый `whenComplete()`;
- callback выполняется при завершении `source` — как успешно, так и с исключением;
- при успешном завершении `source` и нормальном завершении callback `dependent` получает то же значение;
- если `source` завершился с исключением, `dependent` также завершается с этим исключением;
- если callback выбросит исключение при успешном завершении `source`, `dependent` завершится с исключением из callback.

Таким образом, `whenComplete()` предназначен прежде всего для побочных действий (например, логирования), а не для преобразования результата. Как и другие методы композиции, он возвращает отдельный dependent future.

---

# 5. Как связать FutureTask и CompletableFuture

Это решение, к которому мы пришли для CPU-обработки в `SearchPipeline`.

Нам нужны два разных механизма:

- `FutureTask` — чтобы иметь возможность прервать worker;
- `CompletableFuture` — чтобы встроить результат CPU-обработки в существующую асинхронную цепочку.

Схема:

```text
FutureTask<List<T>>
    |
    | done() переносит результат
    v
CompletableFuture<List<T>> cf
    |
    | handle()
    v
CompletableFuture<ProcessingBox<List<T>>> finalCf
```

## 5.1. Для чего нужен `FutureTask.done()`

`FutureTask` предоставляет метод:

```java
protected void done()
```

Он вызывается при завершении задачи:

- успешно;
- с исключением;
- в результате отмены.

Внутри `done()` можно вызвать `get()`: задача уже завершена, поэтому ожидания результата не происходит.

Например:

```java
FutureTask<Integer> task = new FutureTask<>(() -> 42) {
    @Override
    protected void done() {
        try {
            result.complete(get());
        } catch (CancellationException e) {
            result.cancel(false);
        } catch (ExecutionException e) {
            result.completeExceptionally(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.completeExceptionally(e);
        }
    }
};
```

Почему при `ExecutionException` берём `getCause()`?

Потому что `ExecutionException` — обёртка, которую `Future.get()` использует для передачи исключения, возникшего внутри `Callable`. Нам обычно нужно исходное исключение, чтобы корректно классифицировать ошибку.

Почему при `InterruptedException` передаём сам `e`?

Потому что это самостоятельное исключение, а не обёртка над исключением из `Callable`. Также восстанавливаем interrupt-флаг текущего потока.

## 5.2. Почему CancellationContext хранит пару

```java
FutureTask<?> task;
CompletableFuture<?> result;
```

Они представляют разные аспекты одной операции:

- `task` — физическая задача, которую можно попытаться прервать;
- `result` — логический результат, который нужно отменить в асинхронной цепочке.

Если хранить только `CompletableFuture`, мы не сможем надёжно прервать worker.

Если хранить только `FutureTask`, не сможем непосредственно завершить связанный `CompletableFuture`, который участвует в pipeline.

## 5.3. Почему регистрируем `cf`, а не `finalCf`

В нашем `processingGeneric()`:

```java
CompletableFuture<List<T>> cf = new CompletableFuture<>();

CompletableFuture<ProcessingBox<List<T>>> finalCf =
    cf.handle(...);
```

`cf` — результат непосредственно CPU-задачи.

`finalCf` — зависимый future, который преобразует результат в `ProcessingBox`.

Отмена `finalCf` сама по себе не отменит `cf` или `FutureTask`.

Поэтому в `CancellationContext` регистрируем:

```java
cancellationContext.register(ft, cf);
```

А `finalCf` возвращаем вызывающему коду.

---

# 6. Гонки и многопоточность

Ещё одна важная тема, которую мы разобрали, — отмена может происходить одновременно с регистрацией и запуском задачи.

Например:

- T1 — поток, вызывающий `processingGeneric()`;
- T2 — worker-поток;
- T3 — поток, инициировавший отмену запроса.

Возможна последовательность:

1. T1 регистрирует `FutureTask` в `CancellationContext`.
2. T3 отменяет контекст.
3. T3 вызывает `FutureTask.cancel(true)`.
4. `FutureTask` переходит в cancelled state.
5. T1 вызывает `executor.execute(ft)`.

Что произойдёт на шаге 5?

Если `FutureTask` уже отменён до начала выполнения, его `run()` не запустит `Callable`.

То есть повторная попытка передать его Executor не приведёт к выполнению CPU-обработки.

## 6.1. Зачем синхронизировать `register()` и `cancel()`

Нужно исключить такую ситуацию:

1. `register()` проверил `cancelled == false`.
2. `cancel()` установил `cancelled = true` и отменил уже зарегистрированные задачи.
3. `register()` добавил новую задачу после того, как `cancel()` закончил обход списка.

В результате задача не была бы отменена.

Поэтому изменение флага и регистрация защищены одним монитором.

При этом фактическую отмену выполняем за пределами `synchronized`:

```java
synchronized (this) {
    if (cancelled) {
        return;
    }

    cancelled = true;
    operationsToCancel = List.copyOf(operations);
    operations.clear();
}

for (RegisteredOperation operation : operationsToCancel) {
    cancelOperation(operation);
}
```

Это важно, поскольку отмена `CompletableFuture` может синхронно запустить callbacks, которые, в свою очередь, могут обратиться к `CancellationContext`.

---

# 7. Ошибки и отмена — разные сценарии

В `SearchPipeline` мы договорились различать:

- ошибку источника;
- ошибку CPU-обработки;
- отмену запроса.

Например, если фильтрация выбросила `IllegalArgumentException`, мы можем преобразовать её в `ProcessingError`.

Но если запрос отменён, нельзя превращать отмену в обычную ошибку фильтрации и возвращать fallback.

Поэтому в `handle()` сначала проверяем отмену:

```java
Throwable cause = unwrap(ex);

if (cause instanceof CancellationException cancellationException) {
    throw cancellationException;
}
```

И только потом обрабатываем обычные исключения.

Это позволяет отмене распространяться дальше как отмене, а не как успешному результату с ошибкой обработки.

---

# 8. Итоговые правила, которые стоит запомнить

Я бы выделил следующие 15 правил как наиболее важные:

1. `Runnable` описывает задачу без возвращаемого значения, `Callable<T>` — задачу с результатом.
2. `Future<T>` — интерфейс управления результатом асинхронной задачи.
3. `FutureTask<T>` одновременно является `Runnable` и `Future<T>`.
4. `Executor.execute()` выполняет переданную задачу; `ExecutorService.submit()` дополнительно возвращает `Future`.
5. `FutureTask.cancel(true)` может прервать выполняющийся worker через interrupt.
6. Interrupt не гарантирует остановку задачи: задача должна реагировать на прерывание.
7. `CompletableFuture.cancel(true)` не прерывает worker, запущенный через `supplyAsync()` или `runAsync()`.
8. `CompletableFuture` обеспечивает композицию результатов, но не управление отменой всей цепочки.
9. Отмена upstream обычно завершает downstream stages исключением, но отмена downstream не отменяет upstream.
10. `thenCompose()` объединяет результаты вложенных future, но не обеспечивает двустороннюю отмену.
11. Для CPU-задачи, которой нужна реальная возможность прерывания, полезно связать `FutureTask` и `CompletableFuture`.
12. Логическую отмену запроса и физическое прерывание его операций необходимо проектировать отдельно.
13. Завершение upstream Future не зависит от downstream Future: завершение или отмена одного stage не завершает его предшественников.
14. Исключительное завершение распространяется вниз по цепочке, но зависимые Future, завершившиеся вследствие отмены upstream, обычно имеют `isCancelled() == false`.
15. `whenComplete()` создаёт новый dependent future; он предназначен для побочных действий и сохраняет результат или исключение upstream, если сам callback не меняет исход завершения.

---

## Что это означает для нашего SearchPipeline

Мы теперь можем сформулировать архитектурный принцип:

**`CompletableFuture` управляет асинхронным потоком результатов, а `CancellationContext` управляет жизненным циклом операций.**