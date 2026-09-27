package com.infradesk.storage;

import java.nio.file.Path;
import java.util.Locale;

/** Per-OS location of the app's settings directory. */
public final class AppPaths {

    private AppPaths() {
    }

    /**
     * macOS: ~/Library/Application Support/InfraDesk, Windows: %APPDATA%\InfraDesk,
     * other: $XDG_CONFIG_HOME/infradesk or ~/.config/infradesk.
     */
    public static Path configDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path home = Path.of(System.getProperty("user.home"));
        if (os.contains("mac")) {
            return home.resolve("Library").resolve("Application Support").resolve("InfraDesk");
        }
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return (appData != null ? Path.of(appData) : home.resolve("AppData").resolve("Roaming")).resolve("InfraDesk");
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        return (xdg != null ? Path.of(xdg) : home.resolve(".config")).resolve("infradesk");
    }
}
