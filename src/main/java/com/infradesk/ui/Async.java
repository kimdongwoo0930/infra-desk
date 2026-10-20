package com.infradesk.ui;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/** 블로킹 작업을 EDT 밖에서 실행하고 결과를 다시 EDT에서 전달한다. */
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

    /** 실패에 대해 사용자에게 보여줄 메시지. 스택 트레이스는 절대 보여주지 않는다. */
    public static String message(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getMessage() == null) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : "알 수 없는 오류가 발생했어요";
    }
}
