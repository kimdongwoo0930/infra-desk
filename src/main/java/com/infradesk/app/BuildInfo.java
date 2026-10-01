package com.infradesk.app;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 빌드 시점에 Gradle이 기록한 버전, 빌드 번호, 커밋.
 *
 * @param build 베타 빌드는 CI 실행 번호, 로컬 빌드는 "dev"
 */
public record BuildInfo(String version, String build, String commit) {

    private static final BuildInfo CURRENT = load();

    public static BuildInfo current() {
        return CURRENT;
    }

    /** CI 베타 빌드(숫자 빌드 번호)이면 true. 이런 빌드만 업데이트 확인에 참여한다. */
    public boolean isBeta() {
        return buildNumber() > 0;
    }

    /** 숫자 빌드 번호. 개발 빌드는 -1. */
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
            // 기본값으로 넘어간다.
        }
        return new BuildInfo(p.getProperty("version", "?"), p.getProperty("build", "dev"), p.getProperty("commit", "unknown"));
    }
}
