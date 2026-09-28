package com.infradesk.app;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Version, build number and commit written by Gradle at build time.
 *
 * @param build CI run number for beta builds, "dev" for local builds
 */
public record BuildInfo(String version, String build, String commit) {

    private static final BuildInfo CURRENT = load();

    public static BuildInfo current() {
        return CURRENT;
    }

    /** True for CI beta builds (numeric build number); those take part in update checks. */
    public boolean isBeta() {
        return buildNumber() > 0;
    }

    /** Numeric build number, or -1 for dev builds. */
    public int buildNumber() {
        try {
            return Integer.parseInt(build);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** "1.0.0 Beta (빌드 12 · f4102ce)" or "1.0.0 개발 빌드 (f4102ce)". */
    public String display() {
        return isBeta()
                ? version + " Beta (빌드 " + build + " · " + commit + ")"
                : version + " 개발 빌드 (" + commit + ")";
    }

    private static BuildInfo load() {
        Properties p = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream("build-info.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException ignored) {
            // Fall through to defaults.
        }
        return new BuildInfo(p.getProperty("version", "?"), p.getProperty("build", "dev"), p.getProperty("commit", "unknown"));
    }
}
