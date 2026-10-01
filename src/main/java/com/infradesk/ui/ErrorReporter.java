package com.infradesk.ui;

import com.infradesk.ui.components.Toast;

import java.awt.Frame;
import java.awt.Window;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;

/**
 * 아무도 처리하지 않은 예외(EDT를 포함한 모든 스레드)를 잡아서 스택 트레이스와 함께 로그에 남기고,
 * 조용히 실패하는 대신 가끔 사용자에게 알린다.
 */
public final class ErrorReporter {

    private static final Logger LOG = Logger.getLogger(ErrorReporter.class.getName());
    private static final long TOAST_INTERVAL_MS = 30_000;
    private static volatile long lastToast;

    private ErrorReporter() {
    }

    public static void install() {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> report(thread.getName(), error));
    }

    static void report(String where, Throwable error) {
        LOG.log(Level.SEVERE, "Unhandled error in " + where, error);
        long now = System.currentTimeMillis();
        if (now - lastToast < TOAST_INTERVAL_MS) {
            return;
        }
        lastToast = now;
        SwingUtilities.invokeLater(() -> Toast.show(activeWindow(), "InfraDesk 오류",
                "예상치 못한 오류가 발생했어요",
                String.valueOf(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage())
                        + "\n자세한 내용은 설정 → 앱 정보 → 로그 폴더 열기",
                Theme.DANGER_TEXT));
    }

    private static Window activeWindow() {
        Window w = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        if (w != null) {
            return w;
        }
        for (Frame f : Frame.getFrames()) {
            if (f.isShowing()) {
                return f;
            }
        }
        return null;
    }

    /** 로그 폴더를 Finder / 탐색기에서 연다. */
    public static void openLogFolder(java.nio.file.Path dir) {
        try {
            java.nio.file.Files.createDirectories(dir);
            java.awt.Desktop.getDesktop().open(dir.toFile());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not open log folder " + dir, e);
        }
    }
}
