package com.infradesk.ui;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/** Runs blocking work off the EDT and delivers the result back on the EDT. */
public final class Async {

    private static final ExecutorService POOL = Executors.newVirtualThreadPerTaskExecutor();

    private Async() {
    }

    public static <T> void run(Callable<T> task, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        POOL.submit(() -> {
            try {
                T result = task.call();
                SwingUtilities.invokeLater(() -> onSuccess.accept(result));
            } catch (Throwable t) {
                SwingUtilities.invokeLater(() -> onError.accept(t));
            }
        });
    }

    /** User-facing message for a failure, never a stack trace. */
    public static String message(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getMessage() == null) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : "알 수 없는 오류가 발생했어요";
    }
}
