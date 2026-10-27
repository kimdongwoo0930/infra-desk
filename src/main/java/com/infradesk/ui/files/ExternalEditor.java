package com.infradesk.ui.files;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/** 파일을 이 컴퓨터의 편집기로 연다. macOS와 Windows가 다르게 동작해서 OS별로 나눈다. */
final class ExternalEditor {

    /** 개발용 스냅샷이 실제 편집기를 띄우지 않고 저장을 흉내 내려고 바꿔 끼운다. 평소에는 null. */
    static java.util.function.Consumer<Path> launcherOverride;

    private ExternalEditor() {
    }

    /** @throws IOException 편집기를 열지 못했을 때 */
    static void open(Path file) throws IOException {
        if (launcherOverride != null) {
            launcherOverride.accept(file);
            return;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            // 확장자를 모르는 설정 파일(.conf 등)도 열리도록 기본 텍스트 편집기로 연다.
            new ProcessBuilder("open", "-t", file.toString()).start();
            return;
        }
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.EDIT)) {
                    desktop.edit(file.toFile());
                    return;
                }
                if (desktop.isSupported(Desktop.Action.OPEN)) {
                    desktop.open(file.toFile());
                    return;
                }
            }
        } catch (IOException | UnsupportedOperationException e) {
            // 연결된 프로그램이 없다. 아래 기본 편집기로 넘어간다.
        }
        if (os.contains("win")) {
            new ProcessBuilder("notepad", file.toString()).start();
        } else {
            new ProcessBuilder("xdg-open", file.toString()).start();
        }
    }
}
