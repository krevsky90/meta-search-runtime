package org.krev.metasearch.runtime.di;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import org.krev.metasearch.runtime.config.SearchRuntimeConfig;
import org.krev.metasearch.runtime.error_model.SourceTimeouts;

import java.time.Duration;

/**
 * Потом можно будет заменить реализацию provider на чтение из application.properties, YAML или environment variables.
 * Конструктор SearchPipeline при этом не изменится.
 */
public class ConfigModule extends AbstractModule {
    @Provides
    @Singleton
    SearchRuntimeConfig provideSearchRuntimeConfig() {
        SourceTimeouts sourceTimeouts = new SourceTimeouts(
                Duration.ofMillis(300),
                Duration.ofMillis(300),
                Duration.ofMillis(300)
        );

        return new SearchRuntimeConfig(
                Duration.ofMillis(500),
                30L,
                sourceTimeouts
        );
    }
}