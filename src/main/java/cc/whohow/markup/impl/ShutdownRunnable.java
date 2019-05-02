package cc.whohow.markup.impl;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public class ShutdownRunnable implements Runnable {
    private static final Logger log = LogManager.getLogger();

    private final ExecutorService executor;

    public ShutdownRunnable(ExecutorService executor) {
        this.executor = executor;
    }

    public static void shutdown(ExecutorService executor) {
        try {
            if (executor != null) {
                executor.shutdownNow();
                executor.awaitTermination(3, TimeUnit.SECONDS);
            }
        } catch (Throwable e) {
            log.error("close", e);
        }
    }

    @Override
    public void run() {
        shutdown(executor);
    }
}
