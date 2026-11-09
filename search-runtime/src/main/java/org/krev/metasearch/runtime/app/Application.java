package org.krev.metasearch.runtime.app;

import java.util.concurrent.CountDownLatch;

public class Application {
    public static void main(String[] args) throws InterruptedException {
        RuntimeApplication application  = RuntimeApplication.start();

        // При завершении JVM сработает shutdown hook,
        // который вызовет RuntimeApplication.close(),
        // а тот закроет SearchPipeline и его executor.
        Runtime.getRuntime().addShutdownHook(
                new Thread(application::close, "runtime-shutdown")
        );

        System.out.println("Meta Search Runtime started");

        // Пока у приложения нет gRPC/HTTP-сервера.
        // Оставляем процесс работающим до сигнала завершения.
        new CountDownLatch(1).await();

    }
}
