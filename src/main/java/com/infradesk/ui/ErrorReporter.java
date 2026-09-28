package com.infradesk.ui;

import com.infradesk.ui.components.Toast;

import java.awt.Frame;
import java.awt.Window;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;

/**
 * Catches exceptions nobody handled (on any thread, including the EDT), logs them with the stack
 * trace, and tells the user once in a while instead of failing silently.
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

    /** Opens the log folder in Finder / Explorer. */
    public static void openLogFolder(java.nio.file.Path dir) {
        try {
            java.nio.file.Files.createDirectories(dir);
            java.awt.Desktop.getDesktop().open(dir.toFile());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not open log folder " + dir, e);
        }
    }
}
