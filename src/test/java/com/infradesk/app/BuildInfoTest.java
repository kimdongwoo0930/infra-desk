package com.infradesk.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildInfoTest {

    @Test
    void releaseAndDevDisplay() {
        BuildInfo release = new BuildInfo("1.0.0", "12", "f4102ce");
        assertTrue(release.isRelease());
        assertEquals(12, release.buildNumber());
        assertEquals("1.0.0 (빌드 12 · f4102ce)", release.display(), "정식 릴리스에는 Beta가 붙지 않는다");

        BuildInfo dev = new BuildInfo("1.0.0", "dev", "f4102ce");
        assertFalse(dev.isRelease());
        assertEquals("1.0.0 개발 빌드 (f4102ce)", dev.display());
    }

    @Test
    void generatedResourceIsOnTheClasspath() {
        assertFalse(BuildInfo.current().version().equals("?"), "build-info.properties missing");
    }
}
