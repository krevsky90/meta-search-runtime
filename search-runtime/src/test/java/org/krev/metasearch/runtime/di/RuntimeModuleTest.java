package org.krev.metasearch.runtime.di;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;

import org.junit.jupiter.api.Test;

import org.krev.metasearch.runtime.config.SearchRuntimeConfig;
import org.krev.metasearch.runtime.error_model.SourceTimeouts;
import org.krev.metasearch.runtime.external.SearchSourceClient;
import org.krev.metasearch.runtime.search.SearchPipeline;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RuntimeModuleTest {
    @Test
    void shouldCreateSearchPipelineWithInjectedDependencies() {
        SearchSourceClient sourceClient = mock(SearchSourceClient.class);

        SearchRuntimeConfig config = new SearchRuntimeConfig(
                Duration.ofMillis(500),
                5L,
                new SourceTimeouts(
                        Duration.ofMillis(300),
                        Duration.ofMillis(300),
                        Duration.ofMillis(300)
                )
        );

        Injector injector = Guice.createInjector(
                new RuntimeModule(),
                new AbstractModule() {
                    @Override
                    protected void configure() {
                        bind(SearchSourceClient.class).toInstance(sourceClient);
                        bind(SearchRuntimeConfig.class).toInstance(config);
                    }
                }
        );

        SearchPipeline first = injector.getInstance(SearchPipeline.class);
        SearchPipeline second = injector.getInstance(SearchPipeline.class);

        assertThat(first).isSameAs(second);

        assertThat(injector.getInstance(ThreadPoolExecutor.class))
                .isNotNull();

        first.close();
    }

    @Test
    void shouldCreateRuntimeUsingProductionModules() {
        Injector injector = Guice.createInjector(
                new ConfigModule(),
                new RuntimeModule(),
                new DownstreamModule()
        );

        SearchPipeline first = injector.getInstance(SearchPipeline.class);
        SearchPipeline second = injector.getInstance(SearchPipeline.class);

        ThreadPoolExecutor executor1 = injector.getInstance(ThreadPoolExecutor.class);
        ThreadPoolExecutor executor2 = injector.getInstance(ThreadPoolExecutor.class);

        SearchSourceClient client1 = injector.getInstance(SearchSourceClient.class);
        SearchSourceClient client2 = injector.getInstance(SearchSourceClient.class);

        assertThat(first).isSameAs(second);
        assertThat(executor1).isSameAs(executor2);
        assertThat(client1).isSameAs(client2);

        first.close();

        assertThat(executor1.isShutdown()).isTrue();
    }
}