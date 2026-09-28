package com.infradesk.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Starts InfraDesk at login, hidden in the menu bar ({@code --minimized}).
 * macOS: a LaunchAgent plist that runs {@code open -g -a <app>}; Windows: the HKCU Run key.
 * The target is the running packaged app, or else an installed /Applications/InfraDesk.app
 * (so it can be set up from a {@code ./gradlew run} session too).
 */
public final class LaunchAtLogin {

    static final String LABEL = "com.infradesk.app.launcher";
    private static final String RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final Logger LOG = Logger.getLogger(LaunchAtLogin.class.getName());

    private final Path launchAgentsDir;
    private final Optional<Path> target;
    private final boolean mac;
    private final boolean windows;

    LaunchAtLogin(Path launchAgentsDir, Optional<Path> target, boolean mac, boolean windows) {
        this.launchAgentsDir = launchAgentsDir;
        this.target = target;
        this.mac = mac;
        this.windows = windows;
    }

    public static LaunchAtLogin forCurrentOs() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean mac = os.contains("mac");
        boolean win = os.contains("win");
        Path home = Path.of(System.getProperty("user.home"));
        return new LaunchAtLogin(home.resolve("Library").resolve("LaunchAgents"), findTarget(mac, win), mac, win);
    }

    /** macOS: the .app bundle; Windows: InfraDesk.exe. Empty when nothing installed can be launched. */
    static Optional<Path> findTarget(boolean mac, boolean windows) {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null) {
            Path launcher = Path.of(appPath);
            if (mac) {
                // .../InfraDesk.app/Contents/MacOS/InfraDesk → .../InfraDesk.app
                Path bundle = launcher.getParent() == null ? null : launcher.getParent().getParent().getParent();
                return Optional.ofNullable(bundle);
            }
            return Optional.of(launcher);
        }
        if (mac) {
            Path installed = Path.of("/Applications/InfraDesk.app");
            if (Files.isDirectory(installed)) {
                return Optional.of(installed);
            }
        }
        return Optional.empty();
    }

    public boolean isSupported() {
        return (mac || windows) && target.isPresent();
    }

    public Optional<Path> target() {
        return target;
    }

    public boolean isEnabled() {
        if (mac) {
            return Files.exists(plist());
        }
        if (windows) {
            return run("reg", "query", RUN_KEY, "/v", "InfraDesk") == 0;
        }
        return false;
    }

    public void setEnabled(boolean enabled) {
        if (!isSupported()) {
            throw new IllegalStateException("설치한 InfraDesk를 찾지 못해 자동 실행을 설정할 수 없어요");
        }
        try {
            if (mac) {
                if (enabled) {
                    Files.createDirectories(launchAgentsDir);
                    Files.writeString(plist(), plistContent(target.get()));
                } else {
                    Files.deleteIfExists(plist());
                }
            } else if (windows) {
                int code = enabled
                        ? run("reg", "add", RUN_KEY, "/v", "InfraDesk", "/t", "REG_SZ",
                                "/d", "\"" + target.get() + "\" --minimized", "/f")
                        : run("reg", "delete", RUN_KEY, "/v", "InfraDesk", "/f");
                if (code != 0 && enabled) {
                    throw new IllegalStateException("레지스트리에 자동 실행을 등록하지 못했어요");
                }
            }
            LOG.info(() -> "Launch at login " + (enabled ? "enabled → " + target.get() : "disabled"));
        } catch (IOException e) {
            throw new IllegalStateException("자동 실행 설정을 바꾸지 못했어요: " + e.getMessage(), e);
        }
    }

    Path plist() {
        return launchAgentsDir.resolve(LABEL + ".plist");
    }

    /** LaunchAgent that opens the app in the background, hidden in the menu bar. */
    static String plistContent(Path app) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                    <key>Label</key>
                    <string>%s</string>
                    <key>ProgramArguments</key>
                    <array>
                        <string>/usr/bin/open</string>
                        <string>-g</string>
                        <string>-a</string>
                        <string>%s</string>
                        <string>--args</string>
                        <string>--minimized</string>
                    </array>
                    <key>RunAtLoad</key>
                    <true/>
                </dict>
                </plist>
                """.formatted(LABEL, xml(app.toString()));
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static int run(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor();
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }
}
