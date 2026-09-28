package com.infradesk.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildInfoTest {

    @Test
    void betaAndDevDisplay() {
        BuildInfo beta = new BuildInfo("1.0.0", "12", "f4102ce");
        assertTrue(beta.isBeta());
        assertEquals(12, beta.buildNumber());
        assertEquals("1.0.0 Beta (빌드 12 · f4102ce)", beta.display());

        BuildInfo dev = new BuildInfo("1.0.0", "dev", "f4102ce");
        assertFalse(dev.isBeta());
        assertEquals("1.0.0 개발 빌드 (f4102ce)", dev.display());
    }

    @Test
    void generatedResourceIsOnTheClasspath() {
        assertFalse(BuildInfo.current().version().equals("?"), "build-info.properties missing");
    }
}
