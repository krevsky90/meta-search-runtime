package org.krev.metasearch.runtime.di;

import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import org.krev.metasearch.runtime.external.SearchSourceClient;
import org.krev.metasearch.runtime.external.UnconfiguredSearchSourceClient;

public class DownstreamModule extends AbstractModule {
    //todo: replace stub with real GrpcSearchSourceClient implementation
    @Override
    protected void configure() {
        bind(SearchSourceClient.class)
                .to(UnconfiguredSearchSourceClient.class)
                .in(Singleton.class);
    }
}