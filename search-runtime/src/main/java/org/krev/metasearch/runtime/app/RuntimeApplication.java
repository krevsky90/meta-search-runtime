package org.krev.metasearch.runtime.app;

import com.google.inject.Guice;
import com.google.inject.Injector;
import org.krev.metasearch.runtime.di.ConfigModule;
import org.krev.metasearch.runtime.di.DownstreamModule;
import org.krev.metasearch.runtime.di.RuntimeModule;
import org.krev.metasearch.runtime.search.SearchPipeline;

public class RuntimeApplication implements AutoCloseable {
    private final SearchPipeline searchPipeline;

    public RuntimeApplication(SearchPipeline searchPipeline) {
        this.searchPipeline = searchPipeline;
    }

    public static RuntimeApplication start() {
        // collect dependency graph
        Injector injector = Guice.createInjector(
                new ConfigModule(),
                new RuntimeModule(),
                new DownstreamModule()
        );

        // guice creates searchPipeline and resolves all dependencies
        SearchPipeline searchPipeline = injector.getInstance(SearchPipeline.class);

        return new RuntimeApplication(searchPipeline);
    }

    public SearchPipeline searchPipeline() {
        return searchPipeline;
    }

    @Override
    public void close() {
        searchPipeline.close();
    }
}
