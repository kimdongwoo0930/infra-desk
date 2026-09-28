package com.infradesk.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchAtLoginTest {

    @TempDir
    Path dir;

    @Test
    void macLaunchAgentIsWrittenAndRemoved() throws Exception {
        Path app = Files.createDirectories(dir.resolve("Apps & Tools/InfraDesk.app"));
        LaunchAtLogin login = new LaunchAtLogin(dir.resolve("LaunchAgents"), Optional.of(app), true, false);
        assertTrue(login.isSupported());
        assertFalse(login.isEnabled());

        login.setEnabled(true);
        assertTrue(login.isEnabled());
        String plist = Files.readString(login.plist());
        assertTrue(plist.contains("<string>--minimized</string>"));
        // Separator-agnostic: Windows paths use '\\'.
        assertTrue(plist.contains(app.toString().replace("&", "&amp;")), "path is XML-escaped");
        assertFalse(plist.contains("Apps & Tools"), "raw '&' would break the plist");
        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(login.plist().toFile());
        assertEquals("plist", doc.getDocumentElement().getTagName());

        login.setEnabled(false);
        assertFalse(login.isEnabled());
    }

    @Test
    void unsupportedWithoutInstalledApp() {
        LaunchAtLogin login = new LaunchAtLogin(dir, Optional.empty(), true, false);
        assertFalse(login.isSupported());
        assertThrows(IllegalStateException.class, () -> login.setEnabled(true));
    }

    @Test
    void packagedAppResolvesItsBundle() {
        String old = System.getProperty("jpackage.app-path");
        try {
            System.setProperty("jpackage.app-path", "/Applications/InfraDesk.app/Contents/MacOS/InfraDesk");
            assertEquals(Path.of("/Applications/InfraDesk.app"), LaunchAtLogin.findTarget(true, false).orElseThrow());
            System.setProperty("jpackage.app-path", "C:\\Apps\\InfraDesk\\InfraDesk.exe");
            assertTrue(LaunchAtLogin.findTarget(false, true).orElseThrow().toString().endsWith("InfraDesk.exe"));
        } finally {
            if (old == null) {
                System.clearProperty("jpackage.app-path");
            } else {
                System.setProperty("jpackage.app-path", old);
            }
        }
    }
}
