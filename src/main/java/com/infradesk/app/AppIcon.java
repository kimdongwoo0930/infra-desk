package com.infradesk.app;

import java.awt.Image;
import java.awt.Taskbar;
import java.awt.Window;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** The app icon at runtime: Dock (macOS) / taskbar and window icons. Drawn by IconGenerator. */
public final class AppIcon {

    private AppIcon() {
    }

    public static List<Image> images() {
        List<Image> images = new ArrayList<>();
        for (int size : new int[] {64, 128, 256, 512}) {
            try (InputStream in = AppIcon.class.getResourceAsStream("icon-" + size + ".png")) {
                if (in != null) {
                    images.add(ImageIO.read(in));
                }
            } catch (IOException ignored) {
                // Missing size: use the others.
            }
        }
        return images;
    }

    /** Sets the Dock/taskbar icon (needed when running from Gradle; the packaged app has its own). */
    public static void applyToTaskbar() {
        List<Image> images = images();
        if (images.isEmpty() || !Taskbar.isTaskbarSupported()
                || !Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
            return;
        }
        Taskbar.getTaskbar().setIconImage(images.getLast());
    }

    public static void applyTo(Window window) {
        window.setIconImages(images());
    }
}
