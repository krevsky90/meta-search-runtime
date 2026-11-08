package org.krev.metasearch.runtime.search;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;

class CountingFuture<V> extends FutureTask<V> {
    private final AtomicInteger cancelCount = new AtomicInteger();

    CountingFuture(Callable<V> callable) {
        super(callable);
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        cancelCount.incrementAndGet();
        return super.cancel(mayInterruptIfRunning);
    }

    int getCancelCount() {
        return cancelCount.get();
    }
}