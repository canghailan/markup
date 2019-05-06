package cc.whohow.markup.impl;

import java.io.Closeable;
import java.io.IOException;

public class CloseRunnable implements Closeable, Runnable {
    private final Closeable closeable;

    public CloseRunnable(Closeable closeable) {
        this.closeable = closeable;
    }

    @Override
    public void close() throws IOException {
        closeable.close();
    }

    @Override
    public void run() {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (Throwable ignore) {
        }
    }
}
