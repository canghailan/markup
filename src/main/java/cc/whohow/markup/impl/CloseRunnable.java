package cc.whohow.markup.impl;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.Closeable;
import java.io.IOException;

public class CloseRunnable implements Closeable, Runnable {
    private static final Logger log = LogManager.getLogger();

    private final Closeable closeable;

    public CloseRunnable(Closeable closeable) {
        this.closeable = closeable;
    }

    public static void close(AutoCloseable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (Throwable e) {
            log.error("close", e);
        }
    }

    @Override
    public void close() throws IOException {
        closeable.close();
    }

    @Override
    public void run() {
        close(closeable);
    }
}
