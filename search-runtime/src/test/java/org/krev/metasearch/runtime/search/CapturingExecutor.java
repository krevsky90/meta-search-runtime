package org.krev.metasearch.runtime.search;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

class CapturingExecutor extends ThreadPoolExecutor {
    private final AtomicReference<Runnable> submittedTask = new AtomicReference<>();

    CapturingExecutor() {
        super(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>()
        );
    }

    // NOTE: execute() не запускает задачу, а только сохраняет её. Поэтому мы можем проверить её состояние после отмены.
    @Override
    public void execute(Runnable command) {
        submittedTask.set(command);
    }

    Runnable submittedTask() {
        return submittedTask.get();
    }

    int submittedTaskCount() {
        return submittedTask.get() == null ? 0 : 1;
    }
}