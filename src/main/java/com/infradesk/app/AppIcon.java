package com.infradesk.app;

import java.awt.Image;
import java.awt.Taskbar;
import java.awt.Window;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** 실행 중 앱 아이콘: Dock(macOS) / 작업 표시줄과 창 아이콘. IconGenerator가 그린다. */
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
                // 없는 크기는 다른 크기를 쓴다.
            }
        }
        return images;
    }

    /** Dock/작업 표시줄 아이콘을 설정한다(Gradle로 실행할 때 필요. 패키징된 앱은 자체 아이콘이 있다). */
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
