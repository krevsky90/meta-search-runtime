package org.krev.metasearch.runtime.di;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * This module is responsible for infra components
 * as of now it is ThreadPoolExecutor
 */
public class RuntimeModule extends AbstractModule {
    @Provides
    @Singleton
    public ThreadPoolExecutor provideCpuExecutor() {
        return new ThreadPoolExecutor(
                8,
                8,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(50),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setName("search-cpu-" + thread.threadId());
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }
}
