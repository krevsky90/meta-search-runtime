# Google Guice: теория и практика

Практическое руководство по Google Guice на примере
`meta-search-runtime`.

Цель --- не просто научиться писать `bind()` и `@Inject`, а понимать: -
какую проблему решает Dependency Injection (DI); - как Guice строит граф
объектов; - где размещать конфигурацию и bindings; - как управлять scope
и жизненным циклом ресурсов; - как подменять зависимости в тестах; -
какие решения упрощают код, а какие превращают DI-контейнер в источник
скрытой сложности.

Примеры ориентированы на Java 25 и Google Guice 7.x.

------------------------------------------------------------------------

## 1. Зачем нужен Dependency Injection

Представим, что `SearchPipeline` самостоятельно создаёт всё, что ему
требуется:

``` java
public class SearchPipeline {
    private final SearchSourceClient sourceClient =
            new GrpcSearchSourceClient("localhost:9090");

    private final ThreadPoolExecutor cpuExecutor =
            new ThreadPoolExecutor(
                    8, 8,
                    0, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(50)
            );

    public SearchPipeline() {
        // ...
    }
}
```

На первый взгляд это удобно: объект полностью готов к работе. Но у
такого подхода есть проблемы.

1.  **Жёсткая связанность.** `SearchPipeline` знает конкретную
    реализацию `GrpcSearchSourceClient`, а не только контракт
    `SearchSourceClient`.
2.  **Сложность тестирования.** Чтобы протестировать pipeline,
    приходится создавать реальные инфраструктурные объекты или добавлять
    специальные конструкторы.
3.  **Скрытая конфигурация.** Размер пула, адрес downstream-сервиса и
    другие параметры зашиты в код.
4.  **Неясное владение ресурсами.** Кто должен закрывать executor или
    gRPC channel?
5.  **Сложность изменения реализации.** Замена gRPC-клиента на mock или
    другую реализацию затрагивает код потребителя.

Dependency Injection меняет направление зависимости: объект не создаёт
свои внешние зависимости, а получает их извне.

``` java
@Singleton
public class SearchPipeline {
    private final SearchSourceClient sourceClient;
    private final ThreadPoolExecutor cpuExecutor;

    @Inject
    public SearchPipeline(
            SearchSourceClient sourceClient,
            ThreadPoolExecutor cpuExecutor
    ) {
        this.sourceClient = sourceClient;
        this.cpuExecutor = cpuExecutor;
    }
}
```

Теперь `SearchPipeline` зависит от абстракции `SearchSourceClient`, а
конкретный выбор реализации вынесен в конфигурацию приложения.

### Важное различие

DI --- это принцип организации зависимостей, а Guice --- один из
инструментов, который помогает этот принцип реализовать.

DI можно реализовать вручную через конструкторы. Guice автоматизирует
создание объектов и связывание интерфейсов с реализациями.

**DI-контейнер не должен становиться обязательным способом создания
любого объекта.** Простые модели, DTO, value objects и локальные объекты
обычно создаются через `new`.

------------------------------------------------------------------------

## 2. Основные термины Guice

  -----------------------------------------------------------------------
  Термин                              Значение
  ----------------------------------- -----------------------------------
  Injector                            Контейнер, который создаёт объекты
                                      и разрешает их зависимости

  Module                              Конфигурация bindings --- правил
                                      создания и связывания объектов

  Binding                             Правило, объясняющее Guice, какой
                                      объект предоставить для зависимости

  `@Inject`                           Аннотация конструктора или
                                      поля/метода, через которую Guice
                                      выполняет injection

  Scope                               Правило повторного использования
                                      экземпляров, например Singleton

  Provider                            Объект или метод, который знает,
                                      как получить экземпляр зависимости

  `Key<T>`                            Идентификатор зависимости: тип
                                      плюс, возможно, qualifier

  Qualifier                           Дополнительная метка, различающая
                                      зависимости одного типа

  `Injector`                          Корневая точка создания графа
                                      объектов приложения
  -----------------------------------------------------------------------

Типичный путь запуска:

``` text
Application
    |
    v
Guice.createInjector(modules...)
    |
    v
Injector
    |
    v
getInstance(SearchPipeline.class)
    |
    v
SearchPipeline
    |
    +--> SearchSourceClient
    |
    +--> ThreadPoolExecutor
    |
    +--> SearchRuntimeConfig
```

Guice рекурсивно разрешает зависимости конструктора. Если для создания
`SearchPipeline` нужен `ThreadPoolExecutor`, Guice сначала должен
понять, как создать executor.

------------------------------------------------------------------------

## 3. Constructor Injection

Основной рекомендуемый способ внедрения зависимостей --- через
конструктор.

``` java
public class SearchPipeline {
    private final SearchSourceClient sourceClient;
    private final SearchRuntimeConfig config;

    @Inject
    public SearchPipeline(
            SearchSourceClient sourceClient,
            SearchRuntimeConfig config
    ) {
        this.sourceClient = Objects.requireNonNull(sourceClient);
        this.config = Objects.requireNonNull(config);
    }
}
```

### Почему constructor injection предпочтительнее

-   **Обязательные зависимости видны явно.** По сигнатуре конструктора
    понятно, что нужно объекту.
-   **Объект нельзя создать в частично инициализированном состоянии.**
    Конструктор должен получить всё необходимое.
-   **Поля можно сделать `final`.** Это упрощает reasoning о состоянии
    объекта и безопасную публикацию.
-   **Обычные unit-тесты не требуют Guice.** Можно передать mock
    напрямую через конструктор.
-   **Зависимости не скрыты.** В отличие от field injection, читателю не
    нужно искать аннотации по всему классу.

Пример unit-теста без Injector:

``` java
SearchSourceClient sourceClient = mock(SearchSourceClient.class);
ThreadPoolExecutor executor = createTestExecutor();
SearchRuntimeConfig config = testConfig();

try (SearchPipeline pipeline =
             new SearchPipeline(sourceClient, executor, config)) {
    // тестируем поведение pipeline
}
```

### Field Injection

Guice умеет внедрять зависимости в поля:

``` java
public class SomeService {
    @Inject
    private SearchSourceClient sourceClient;
}
```

Но для прикладных сервисов это обычно плохой выбор: - объект можно
создать без заполненной зависимости; - поле нельзя сделать `final`; -
unit-тесту сложнее передать зависимость обычным способом; - зависимость
не видна в конструкторе.

### Method Injection

Guice также поддерживает injection через методы. Это бывает полезно для
специальных случаев, но не должно заменять обычный конструктор.

**Практическое правило:** обязательные зависимости --- через constructor
injection. Field injection в обычном application code лучше не
использовать.

------------------------------------------------------------------------

## 4. Modules и bindings

Module описывает, как Guice должен предоставлять зависимости.

``` java
public final class DownstreamModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(SearchSourceClient.class)
                .to(UnconfiguredSearchSourceClient.class)
                .in(Scopes.SINGLETON);
    }
}
```

Здесь задано правило:

> Когда объект запрашивает `SearchSourceClient`, предоставить
> `UnconfiguredSearchSourceClient`, используя singleton scope.

Интерфейс и реализация:

``` java
public interface SearchSourceClient {
    SourceCall<List<Product>> searchCatalog(String query);
    SourceCall<List<Offer>> searchOffers(String query);
    SourceCall<List<Review>> searchReviews(String query);
}
```

``` java
@Singleton
public class UnconfiguredSearchSourceClient
        implements SearchSourceClient {
    // Временная реализация для bootstrap.
}
```

Есть несколько распространённых способов описать binding.

### 4.1. Interface → implementation

``` java
bind(SearchSourceClient.class)
        .to(GrpcSearchSourceClient.class);
```

Используется, когда потребитель зависит от интерфейса, а приложение
выбирает реализацию.

### 4.2. Binding конкретного экземпляра

``` java
SearchSourceClient client = createClient();

bind(SearchSourceClient.class)
        .toInstance(client);
```

Полезно в тестах или когда экземпляр уже создан внешним кодом.

Важно: Guice не управляет полноценным жизненным циклом объекта,
переданного через `toInstance()`, так же как специализированный
контейнер ресурсов. Если объект владеет ресурсами, явно определите, кто
и когда его закрывает.

### 4.3. Provider method

Когда создание зависимости требует параметров или нескольких шагов,
можно использовать `@Provides`.

``` java
public final class RuntimeModule extends AbstractModule {
    @Provides
    @Singleton
    ThreadPoolExecutor provideCpuExecutor() {
        ThreadFactory threadFactory = Thread.ofPlatform()
                .name("search-cpu-", 0)
                .factory();

        return new ThreadPoolExecutor(
                8,
                8,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(50),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy()
        );
    }
}
```

Метод `@Provides` сам является binding. Guice вызывает его, когда
требуется `ThreadPoolExecutor`.

Используйте provider methods для инфраструктурных объектов, которые
неудобно создавать простым constructor injection: executor, client,
connection pool, конфигурация и т. п.

### 4.4. `@ImplementedBy`

Guice поддерживает:

``` java
@ImplementedBy(DefaultSearchSourceClient.class)
public interface SearchSourceClient {
}
```

Но для приложения с несколькими окружениями, тестовыми реализациями и
явной конфигурацией лучше обычно держать выбор реализации в Module. Так
composition root остаётся видимым и централизованным.

------------------------------------------------------------------------

## 5. Конфигурация приложения

Конфигурация --- тоже зависимость. Не стоит читать environment variables
или properties из каждого сервиса.

Для runtime можно определить immutable record:

``` java
public record SearchRuntimeConfig(
        Duration requestTimeout,
        long terminationTimeoutSeconds,
        SourceTimeouts sourceTimeouts
) {
    public SearchRuntimeConfig {
        Objects.requireNonNull(requestTimeout);
        Objects.requireNonNull(sourceTimeouts);

        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "requestTimeout must be positive"
            );
        }

        if (terminationTimeoutSeconds < 0) {
            throw new IllegalArgumentException(
                    "terminationTimeoutSeconds must not be negative"
            );
        }
    }
}
```

Затем предоставить конфигурацию через Module:

``` java
public final class ConfigModule extends AbstractModule {
    @Provides
    @Singleton
    SearchRuntimeConfig provideSearchRuntimeConfig() {
        return new SearchRuntimeConfig(
                Duration.ofMillis(500),
                30,
                new SourceTimeouts(
                        Duration.ofMillis(300),
                        Duration.ofMillis(300),
                        Duration.ofMillis(300)
                )
        );
    }
}
```

На старте проекта defaults допустимы. Позже этот provider можно заменить
чтением из `application.properties`, YAML, environment variables или
централизованного config service.

### Почему не читать конфигурацию внутри SearchPipeline

Плохо:

``` java
public SearchPipeline(SearchSourceClient client) {
    this.requestTimeout = Duration.ofMillis(
            Long.parseLong(System.getenv("REQUEST_TIMEOUT_MS"))
    );
}
```

Такой класс сам отвечает и за бизнес-логику, и за чтение конфигурации, и
за преобразование внешних значений. Это усложняет тестирование и
связывает runtime с конкретным способом конфигурации.

Лучше: прочитать внешние настройки на границе приложения,
провалидировать их и передать в виде типизированного объекта.

------------------------------------------------------------------------

## 6. Scopes: сколько экземпляров создаёт Guice

Scope определяет время жизни и повторное использование экземпляра.

### 6.1. Без scope

``` java
bind(SomeService.class);
```

Если scope не задан, Guice обычно создаёт новый экземпляр при каждом
независимом запросе этого binding. Зависимости внутри конкретного
созданного объекта при этом разрешаются для него обычным образом.

### 6.2. Singleton

``` java
@Singleton
public class SearchPipeline {
}
```

Или через Module:

``` java
bind(SearchPipeline.class).in(Scopes.SINGLETON);
```

Singleton в Guice означает **один экземпляр в рамках конкретного
Injector**.

Это не означает: - один экземпляр на всю JVM; - автоматическую
потокобезопасность; - автоматическую синхронизацию методов; -
автоматическое закрытие ресурсов.

Если создать два Injector, у каждого может быть собственный singleton.

### Какие компоненты разумно делать singleton

Для `meta-search-runtime` потенциально подходят: - `SearchPipeline` ---
если он stateless относительно отдельных запросов и его зависимости
допускают конкурентное использование; - `SearchSourceClient` --- если
клиент потокобезопасен и предназначен для совместного использования; -
`ThreadPoolExecutor` --- обычно один управляемый пул на соответствующую
область runtime; - immutable `SearchRuntimeConfig`.

Не нужно делать singleton каждую модель или каждый вспомогательный
объект только ради экономии создания объектов.

### Singleton и thread safety --- разные вещи

Например:

``` java
@Singleton
public class SearchPipeline {
    private String currentQuery; // опасно при конкурентных запросах
}
```

Один экземпляр одновременно обслуживает много запросов. Если хранить
данные конкретного запроса в полях, запросы могут перезаписывать
состояние друг друга.

Безопаснее держать request-specific state в локальных переменных и
объектах контекста запроса:

``` java
public SearchResultBox search(String query) {
    CancellationContext cancellationContext =
            new CancellationContext();

    // Состояние относится только к этому вызову.
}
```

**Правило:** singleton --- решение о времени жизни объекта, а не
доказательство его потокобезопасности.

------------------------------------------------------------------------

## 7. Qualifiers: несколько зависимостей одного типа

Иногда приложению нужны два executor одного типа: для CPU-bound
обработки и для блокирующего I/O.

Если просто объявить два binding `ThreadPoolExecutor`, Guice не сможет
однозначно выбрать нужный.

Для этого применяются qualifiers.

``` java
@BindingAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({
        ElementType.FIELD,
        ElementType.PARAMETER,
        ElementType.METHOD
})
public @interface CpuExecutor {
}
```

``` java
@BindingAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({
        ElementType.FIELD,
        ElementType.PARAMETER,
        ElementType.METHOD
})
public @interface IoExecutor {
}
```

Bindings:

``` java
@Provides
@Singleton
@CpuExecutor
ThreadPoolExecutor provideCpuExecutor() {
    return createCpuExecutor();
}

@Provides
@Singleton
@IoExecutor
ThreadPoolExecutor provideIoExecutor() {
    return createIoExecutor();
}
```

Injection:

``` java
@Inject
public SomeService(
        @CpuExecutor ThreadPoolExecutor cpuExecutor,
        @IoExecutor ThreadPoolExecutor ioExecutor
) {
    // ...
}
```

Для простых случаев Guice также предоставляет `@Named("cpu")`, но
собственные qualifier-аннотации обычно безопаснее: они типизированы и не
зависят от строковых литералов.

Не вводите qualifiers заранее, если в приложении пока существует только
один executor. Добавляйте их тогда, когда реально появляются разные
зависимости одного типа.

------------------------------------------------------------------------

## 8. Provider и отложенное получение зависимости

Иногда объект нужен не сразу или его создание должно происходить по
требованию. Для этого можно внедрить `Provider<T>`.

``` java
public class ReportService {
    private final Provider<ExpensiveClient> clientProvider;

    @Inject
    public ReportService(Provider<ExpensiveClient> clientProvider) {
        this.clientProvider = clientProvider;
    }

    public void generate() {
        ExpensiveClient client = clientProvider.get();
        // ...
    }
}
```

Provider может быть полезен: - для отложенного создания объекта; - при
необходимости получать новый экземпляр unscoped dependency; - для
разрыва некоторых циклических зависимостей на уровне момента создания.

Но Provider не должен становиться способом скрыть плохую архитектуру.
Если `A` и `B` взаимно зависят друг от друга, часто правильнее
пересмотреть границы ответственности, а не маскировать цикл через
`Provider`.

------------------------------------------------------------------------

## 9. Composition Root и запуск приложения

**Composition Root** --- место, где приложение собирает граф объектов. В
Guice это обычно стартовый код, который создаёт Injector и получает
корневой объект приложения.

``` java
public final class RuntimeApplication implements AutoCloseable {
    private final SearchPipeline searchPipeline;

    public RuntimeApplication(SearchPipeline searchPipeline) {
        this.searchPipeline = Objects.requireNonNull(searchPipeline);
    }

    public static RuntimeApplication start() {
        Injector injector = Guice.createInjector(
                new ConfigModule(),
                new RuntimeModule(),
                new DownstreamModule()
        );

        SearchPipeline pipeline =
                injector.getInstance(SearchPipeline.class);

        return new RuntimeApplication(pipeline);
    }

    @Override
    public void close() {
        searchPipeline.close();
    }
}
```

Точка входа:

``` java
public final class Application {
    public static void main(String[] args) throws InterruptedException {
        RuntimeApplication application = RuntimeApplication.start();

        Runtime.getRuntime().addShutdownHook(
                new Thread(application::close, "runtime-shutdown")
        );

        System.out.println("Meta Search Runtime started");

        new CountDownLatch(1).await();
    }
}
```

Пока у приложения нет gRPC/HTTP server, `CountDownLatch` позволяет
процессу оставаться запущенным. Когда появится сервер, жизненный цикл
приложения должен запускать и останавливать его явно.

### Почему не вызывать Injector повсюду

Плохой вариант:

``` java
public class SearchPipeline {
    public SearchResult search(String query) {
        SearchSourceClient client =
                injector.getInstance(SearchSourceClient.class);
        // ...
    }
}
```

Это Service Locator: класс сам обращается к глобальному контейнеру за
зависимостями.

Проблемы: - зависимости скрыты; - класс связан с Guice; - unit-тесты
требуют настройки Injector; - объект может неожиданно создавать новые
зависимости внутри методов.

Предпочтительный подход: получить зависимости через конструктор, а
Injector использовать на границе приложения.

------------------------------------------------------------------------

## 10. Жизненный цикл и закрытие ресурсов

Guice отвечает за создание объектов, но не следует считать его
полноценным lifecycle-контейнером, который автоматически закроет все
`AutoCloseable` объекты.

Для каждого ресурса нужно определить владельца.

В текущем варианте `SearchPipeline` получает `ThreadPoolExecutor` и
закрывает его в `close()`:

``` java
@Override
public void close() {
    cpuExecutor.shutdown();

    try {
        if (!cpuExecutor.awaitTermination(
                terminationTimeoutSeconds,
                TimeUnit.SECONDS
        )) {
            cpuExecutor.shutdownNow();
        }
    } catch (InterruptedException e) {
        cpuExecutor.shutdownNow();
        Thread.currentThread().interrupt();
    }
}
```

Это допустимо, если соблюдается инвариант:

> SearchPipeline --- единственный компонент, который владеет этим
> executor и отвечает за его завершение.

Если executor начнут использовать несколько независимых компонентов,
закрывать его из `SearchPipeline` станет неправильно. Тогда владение
следует перенести на уровень приложения или отдельного lifecycle
manager.

### Практические правила владения

1.  У каждого ресурса должен быть понятный владелец.
2.  Закрывать ресурс должен владелец, а не любой потребитель.
3.  Закрытие должно быть идемпотентным либо вызываться гарантированно
    один раз.
4.  Shutdown должен учитывать graceful termination и ограниченное время
    ожидания.
5.  При `InterruptedException` обычно нужно восстановить interrupt-флаг
    через `Thread.currentThread().interrupt()`.
6.  Если приложение создаёт несколько ресурсов, порядок закрытия должен
    быть определён.

Примеры ресурсов: - `ExecutorService`; - gRPC `ManagedChannel`; - HTTP
client с собственным connection pool; - Kafka producer/consumer; -
database connection pool.

------------------------------------------------------------------------

## 11. Тестирование Guice-конфигурации

Нужно разделять два вида тестов.

### 11.1. Unit-тест класса

В большинстве unit-тестов Guice не нужен. Передаём зависимости напрямую:

``` java
SearchSourceClient client = mock(SearchSourceClient.class);
ThreadPoolExecutor executor = createTestExecutor();

SearchPipeline pipeline = new SearchPipeline(
        client,
        executor,
        testConfig()
);
```

Преимущества: - тест быстрый; - тестируется поведение класса, а не
DI-конфигурация; - зависимости легко контролировать; - не требуется
поднимать Injector.

### 11.2. Тест wiring --- проверка графа объектов

Отдельный тест проверяет, что production modules собираются вместе и
дают ожидаемые экземпляры.

``` java
@Test
void shouldCreateRuntimeUsingProductionModules() {
    Injector injector = Guice.createInjector(
            new ConfigModule(),
            new RuntimeModule(),
            new DownstreamModule()
    );

    SearchPipeline first =
            injector.getInstance(SearchPipeline.class);
    SearchPipeline second =
            injector.getInstance(SearchPipeline.class);

    ThreadPoolExecutor executor1 =
            injector.getInstance(ThreadPoolExecutor.class);
    ThreadPoolExecutor executor2 =
            injector.getInstance(ThreadPoolExecutor.class);

    SearchSourceClient client1 =
            injector.getInstance(SearchSourceClient.class);
    SearchSourceClient client2 =
            injector.getInstance(SearchSourceClient.class);

    assertThat(first).isSameAs(second);
    assertThat(executor1).isSameAs(executor2);
    assertThat(client1).isSameAs(client2);

    first.close();

    assertThat(executor1.isShutdown()).isTrue();
}
```

Этот тест проверяет: - production modules не конфликтуют; - Guice может
создать полный граф; - singleton scope работает в рамках Injector; -
executor действительно закрывается владельцем.

### 11.3. Подмена зависимостей в тестах

Можно создать тестовый Injector с альтернативным binding:

``` java
SearchSourceClient mockClient =
        mock(SearchSourceClient.class);

Injector injector = Guice.createInjector(
        new RuntimeModule(),
        new AbstractModule() {
            @Override
            protected void configure() {
                bind(SearchRuntimeConfig.class)
                        .toInstance(testConfig());

                bind(SearchSourceClient.class)
                        .toInstance(mockClient);
            }
        }
);
```

Так тест использует реальный runtime wiring, но заменяет внешнюю
зависимость.

Для больших проектов удобно проектировать modules так, чтобы
инфраструктурные реализации можно было заменять без изменения
бизнес-кода.

------------------------------------------------------------------------

## 12. Ошибки и bad practices

### 12.1. Service Locator вместо DI

Плохо:

``` java
SomeDependency dependency =
        injector.getInstance(SomeDependency.class);
```

внутри бизнес-класса.

Лучше получить зависимость через конструктор.

### 12.2. Field injection для обязательных зависимостей

Плохо:

``` java
@Inject
private SearchSourceClient client;
```

Лучше constructor injection.

### 12.3. Один гигантский Module

Плохо, когда в одном классе смешаны: - чтение конфигурации; - создание
executor; - выбор downstream-клиента; - bindings всех
feature-компонентов; - настройки тестовых реализаций.

Лучше разделять modules по ответственности, например: -
`ConfigModule`; - `RuntimeModule`; - `DownstreamModule`.

При этом не нужно создавать отдельный Module на каждый класс: дробление
должно улучшать навигацию, а не усложнять её.

### 12.4. Слишком много singleton

Singleton не является универсальной оптимизацией. Он увеличивает время
жизни объекта и может создать проблемы с конкурентным доступом к
изменяемому состоянию.

### 12.5. Циклические зависимости

Например:

``` text
SearchService -> RankingService
RankingService -> SearchService
```

Это часто сигнал, что обязанности распределены неправильно. Выделите
общий контракт или третий компонент, который координирует оба сервиса.

### 12.6. Слишком много логики в Provider

Provider должен собирать зависимость, а не содержать бизнес-процесс.
Если provider превращается в сложный алгоритм, вынесите создание объекта
в отдельную фабрику или специализированный компонент.

### 12.7. Неясное владение ресурсом

Плохо, когда несколько singleton-компонентов могут независимо закрыть
один executor или client. Назначьте одного владельца и документируйте
lifecycle.

### 12.8. Создание Injector в каждом тесте без необходимости

Unit-тесту не нужен Guice, если он тестирует обычную логику класса.
Injector нужен для отдельных wiring/integration тестов.

------------------------------------------------------------------------

## 13. Guice и Spring: в чём разница

Оба инструмента поддерживают Dependency Injection, но имеют разный
масштаб и философию.

  -----------------------------------------------------------------------
  Область                 Google Guice            Spring Framework
  ----------------------- ----------------------- -----------------------
  Основная задача         DI-контейнер            DI и большая
                                                  инфраструктурная
                                                  экосистема

  Конфигурация            Modules, bindings,      Java config,
                          providers               annotations, component
                                                  scanning, properties

  Автоматическая          Минимальная             Большая экосистема
  инфраструктура                                  модулей и интеграций

  Web stack               Не является частью      Есть Spring MVC и
                          Guice                   WebFlux

  Data access             Сам по себе не          Spring Data, транзакции
                          предоставляет широкий   и интеграции
                          стек                    

  Lifecycle               Нужно явно              Есть развитые
                          организовывать владение lifecycle-механизмы
                          ресурсами               контейнера

  Размер концептуальной   Относительно небольшой  Значительно шире
  поверхности                                     

  Контроль сборки графа   Явные bindings в        Можно использовать как
                          modules                 явную конфигурацию, так
                                                  и component scanning
  -----------------------------------------------------------------------

### Почему Guice может быть хорошим выбором

-   нужен компактный DI-контейнер без большого application framework;
-   хочется явно видеть, откуда берутся зависимости;
-   приложение имеет собственную архитектуру и lifecycle;
-   нужны constructor injection и scopes без всего Spring ecosystem.

### Что Guice не делает за приложение

Guice сам по себе не предоставляет: - HTTP/gRPC server; -
конфигурационный framework уровня Spring Boot; - автоматические
транзакции; - готовую систему health checks; - полноценный production
lifecycle для всех ресурсов; - мониторинг и observability.

Это не недостаток, если приложение сознательно собирает нужную
инфраструктуру отдельно. Но важно не путать «легче Spring» с «всё
остальное появится автоматически».

------------------------------------------------------------------------

## 14. Рекомендуемая структура для meta-search-runtime

Один из возможных вариантов:

``` text
search-runtime/
└── src/main/java/org/krev/metasearch/runtime/
    ├── Application.java
    ├── RuntimeApplication.java
    ├── config/
    │   ├── ConfigModule.java
    │   └── SearchRuntimeConfig.java
    ├── di/
    │   ├── RuntimeModule.java
    │   └── DownstreamModule.java
    ├── search/
    │   ├── SearchPipeline.java
    │   └── ...
    └── downstream/
        ├── SearchSourceClient.java
        ├── GrpcSearchSourceClient.java
        └── UnconfiguredSearchSourceClient.java
```

Это не обязательная структура. Если проект небольшой, не нужно создавать
пакеты только ради следования шаблону. Важнее, чтобы: - composition root
был один и очевидный; - конфигурация была отделена от бизнес-логики; -
инфраструктурные bindings были видны; - зависимости шли от потребителей
к контрактам, а не к конкретным реализациям; - lifecycle ресурсов имел
явного владельца.

------------------------------------------------------------------------

## 15. Чек-лист перед закрытием задачи на Guice bootstrap

-   [ ] Injector создаётся в одной точке приложения.
-   [ ] Все обязательные зависимости прикладных сервисов передаются
    через конструктор.
-   [ ] Нет обращения к Injector из бизнес-классов.
-   [ ] Интерфейсы downstream-клиентов связаны с конкретными
    реализациями в Module.
-   [ ] Конфигурация типизирована и валидируется при создании.
-   [ ] Singleton scope задан осознанно.
-   [ ] Singleton-компоненты не хранят изменяемое состояние отдельных
    запросов.
-   [ ] Нет циклических зависимостей.
-   [ ] Инфраструктурные зависимости можно заменить в тестах.
-   [ ] Есть тест сборки production graph.
-   [ ] Ресурсы закрываются при остановке приложения.
-   [ ] Для каждого ресурса определён единственный владелец.

------------------------------------------------------------------------

## 16. Практические упражнения

Попробуй выполнить упражнения самостоятельно, не заглядывая в следующие
разделы проекта.

### Упражнение 1. Разобрать граф зависимостей

Для `SearchPipeline` нарисуй граф объектов: - какие зависимости нужны
напрямую; - какие зависимости нужны этим зависимостям; - какие из них
singleton; - какие являются ресурсами; - кто отвечает за закрытие
каждого ресурса.

Цель: научиться видеть composition graph до написания Module.

### Упражнение 2. Добавить второй executor

Добавь отдельный executor для blocking I/O: - отдельный qualifier; -
отдельный provider; - внедрение в компонент, которому он нужен; - тест,
проверяющий, что CPU и I/O executor --- разные экземпляры; - определение
владельца обоих пулов и их shutdown.

Не делай оба пула singleton автоматически: сначала обоснуй их жизненный
цикл.

### Упражнение 3. Заменить downstream client в тесте

Создай тестовый `SearchSourceClient`, который: - возвращает заранее
заданные результаты; - умеет возвращать failed future; - позволяет
проверить graceful degradation pipeline.

Собери Injector с тестовым binding и проверь поведение pipeline.

### Упражнение 4. Найти архитектурный запах

Представь, что `SearchPipeline` начинает зависеть от `RankingService`, а
`RankingService` --- от `SearchPipeline`.

Предложи два способа убрать цикл. Объясни, какой из них лучше и почему.

### Упражнение 5. Lifecycle

Добавь в приложение второй ресурс, например gRPC `ManagedChannel`.
Определи: - кто его создаёт; - кто им пользуется; - кто его закрывает; -
что произойдёт, если приложение завершится во время активного запроса; -
в каком порядке закрывать ресурсы.

------------------------------------------------------------------------

## 17. Короткая памятка

``` text
DI:
  Объект получает зависимости извне.

Guice:
  Injector создаёт граф объектов.
  Module описывает bindings.
  @Inject помечает constructor injection.
  @Provides создаёт сложные зависимости.
  Scope определяет повторное использование экземпляров.
  Provider<T> позволяет отложить получение зависимости.

Практика:
  Constructor injection — по умолчанию.
  @Singleton — только при осознанном lifecycle.
  Injector — только в composition root.
  Unit tests — обычное создание через new + mocks.
  Wiring tests — проверка production modules.
  Ресурсы — явный владелец и явный shutdown.

Не путать:
  Singleton != thread-safe.
  Guice != Spring Boot.
  DI != Service Locator.
  Создание объекта != управление его жизненным циклом.
```

Главная идея: Guice должен сделать граф зависимостей явным и
управляемым. Если ради Guice бизнес-классы начинают знать об Injector,
Provider появляется повсюду, а lifecycle ресурсов становится непонятным
--- DI-конфигурация ухудшила архитектуру вместо того, чтобы её
упростить.
