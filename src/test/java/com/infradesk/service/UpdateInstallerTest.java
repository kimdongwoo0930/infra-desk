package com.infradesk.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateInstallerTest {

    @TempDir
    Path dir;

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void readsSha256SumsInBothFormats() {
        String a = "a".repeat(64);
        String b = "B".repeat(64);
        String sums = a + "  InfraDesk-beta-macOS.zip\n" + b + " *InfraDesk-beta-windows.zip\n";
        assertEquals(Optional.of(a), UpdateInstaller.expectedHash(sums, "InfraDesk-beta-macOS.zip"));
        assertEquals(Optional.of("b".repeat(64)), UpdateInstaller.expectedHash(sums, "InfraDesk-beta-windows.zip"));
        assertEquals(Optional.empty(), UpdateInstaller.expectedHash(sums, "other.zip"));
    }

    @Test
    void findsTheInstalledAppFromTheLauncher() {
        assertEquals(Optional.of(Path.of("/Applications/InfraDesk.app")), UpdateInstaller.installedApp(
                UpdateInstaller.Platform.MAC, Path.of("/Applications/InfraDesk.app/Contents/MacOS/InfraDesk")));
        assertEquals(Optional.empty(), UpdateInstaller.installedApp(
                UpdateInstaller.Platform.MAC, Path.of("/opt/infradesk/bin/java")));
        Path exe = Path.of("apps", "InfraDesk", "InfraDesk.exe");
        assertEquals(Optional.of(exe.getParent()), UpdateInstaller.installedApp(UpdateInstaller.Platform.WINDOWS, exe));
    }

    @Test
    void unzipRejectsEntriesOutsideTheTarget() throws IOException {
        Path zip = dir.resolve("evil.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("../escaped.txt"));
            out.write("x".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        Path dest = Files.createDirectories(dir.resolve("dest"));
        assertThrows(IOException.class, () -> UpdateInstaller.unzip(zip, dest));
        assertFalse(Files.exists(dir.resolve("escaped.txt")));
    }

    @Test
    void releasesWithoutUpdateFilesOrUnwritableLocationsAreBlocked() throws IOException {
        Path app = Files.createDirectories(dir.resolve("InfraDesk"));
        UpdateInstaller installer = new UpdateInstaller(HttpClient.newHttpClient(), UpdateInstaller.Platform.WINDOWS,
                app, dir.resolve("work"), dir.resolve("swap.log"), false);
        var old = new UpdateService.Release(9, "", "https://p", "https://d", null, null);
        assertTrue(installer.blocker(old).orElseThrow().contains("자동 설치용 파일이 없어요"));
        var ok = new UpdateService.Release(9, "", "https://p", "https://d", "https://u/a.zip", "https://u/SHA256SUMS.txt");
        assertEquals(Optional.empty(), installer.blocker(ok));
    }

    @Test
    void hashMismatchIsRejectedAndNothingIsStaged() throws Exception {
        Path app = Files.createDirectories(dir.resolve("apps").resolve("InfraDesk"));
        byte[] zip = windowsAppZip("v2");
        String base = serve(zip, "0".repeat(64) + "  InfraDesk-beta-windows.zip\n");
        UpdateInstaller installer = new UpdateInstaller(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
                UpdateInstaller.Platform.WINDOWS, app, dir.resolve("work"), dir.resolve("swap.log"), false);
        var release = new UpdateService.Release(9, "", base, base, base + "/dl/InfraDesk-beta-windows.zip", base + "/sums");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> installer.prepare(release, (d, t) -> { }, () -> false));
        assertTrue(e.getMessage().contains("SHA-256"), e.getMessage());
        assertFalse(Files.exists(app.resolveSibling(UpdateInstaller.STAGING)));
    }

    @Test
    void cancellingStopsTheDownload() throws Exception {
        Path app = Files.createDirectories(dir.resolve("apps").resolve("InfraDesk"));
        byte[] zip = windowsAppZip("v2");
        String base = serve(zip, sha256(zip) + "  InfraDesk-beta-windows.zip\n");
        UpdateInstaller installer = new UpdateInstaller(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
                UpdateInstaller.Platform.WINDOWS, app, dir.resolve("work"), dir.resolve("swap.log"), false);
        var release = new UpdateService.Release(9, "", base, base, base + "/dl/InfraDesk-beta-windows.zip", base + "/sums");
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> installer.prepare(release, (d, t) -> { }, () -> true));
    }

    /** Download (through a redirect, like GitHub's CDN) → verify → unzip → swap, with Java's unzip. */
    @Test
    void preparesAWindowsUpdateFromARedirectedDownload() throws Exception {
        Path app = Files.createDirectories(dir.resolve("apps").resolve("InfraDesk"));
        Files.writeString(app.resolve("InfraDesk.exe"), "v1");
        byte[] zip = windowsAppZip("v2");
        String base = serve(zip, sha256(zip) + "  InfraDesk-beta-windows.zip\n");
        UpdateInstaller installer = new UpdateInstaller(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
                UpdateInstaller.Platform.WINDOWS, app, dir.resolve("work"), dir.resolve("swap.log"), false);
        var release = new UpdateService.Release(9, "", base, base, base + "/dl/InfraDesk-beta-windows.zip", base + "/sums");
        AtomicLong seen = new AtomicLong();
        UpdateInstaller.Prepared prepared = installer.prepare(release, (d, t) -> seen.set(d), () -> false);
        assertEquals(zip.length, seen.get());
        assertEquals("v2", Files.readString(prepared.newApp().resolve("InfraDesk.exe")));
        assertEquals("v1", Files.readString(app.resolve("InfraDesk.exe")), "nothing replaced before the swap");
        assertFalse(Files.exists(dir.resolve("work").resolve("InfraDesk-update-9.zip")), "archive removed");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsSwapReplacesTheAppFolder() throws Exception {
        Path app = Files.createDirectories(dir.resolve("apps").resolve("InfraDesk"));
        Files.writeString(app.resolve("InfraDesk.exe"), "v1");
        byte[] zip = windowsAppZip("v2");
        String base = serve(zip, sha256(zip) + "  InfraDesk-beta-windows.zip\n");
        UpdateInstaller installer = new UpdateInstaller(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
                UpdateInstaller.Platform.WINDOWS, app, dir.resolve("work"), dir.resolve("logs").resolve("swap.log"), false);
        var release = new UpdateService.Release(9, "", base, base, base + "/dl/InfraDesk-beta-windows.zip", base + "/sums");
        UpdateInstaller.Prepared prepared = installer.prepare(release, (d, t) -> { }, () -> false);
        assertSwapped(installer, prepared, app, app.resolve("InfraDesk.exe"));
    }

    /** The real macOS path: ditto zip, ditto unzip, the new app's --self-test, then the sh swap script. */
    @Test
    @EnabledOnOs(OS.MAC)
    void macUpdateSelfTestsAndSwapsTheBundle() throws Exception {
        Path app = fakeMacApp(dir.resolve("Applications"), "v1");
        Path build = fakeMacApp(dir.resolve("build"), "v2");
        Path zipFile = dir.resolve("InfraDesk-beta-macOS.zip");
        Process ditto = new ProcessBuilder("ditto", "-c", "-k", "--keepParent", build.toString(), zipFile.toString()).start();
        assertEquals(0, ditto.waitFor());
        byte[] zip = Files.readAllBytes(zipFile);
        String base = serve(zip, sha256(zip) + "  InfraDesk-beta-macOS.zip\n");
        UpdateInstaller installer = new UpdateInstaller(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
                UpdateInstaller.Platform.MAC, app, dir.resolve("work"), dir.resolve("logs").resolve("swap.log"), true);
        var release = new UpdateService.Release(9, "", base, base, base + "/dl/InfraDesk-beta-macOS.zip", base + "/sums");
        UpdateInstaller.Prepared prepared = installer.prepare(release, (d, t) -> { }, () -> false);
        assertTrue(Files.readString(dir.resolve("work").resolve("update-self-test.log")).contains("SELF-TEST OK v2"));
        assertSwapped(installer, prepared, app, app.resolve("Contents/MacOS/version.txt"));
        assertTrue(Files.isExecutable(app.resolve("Contents/MacOS/InfraDesk")), "exec bit kept");
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void macUpdateThatFailsItsSelfTestIsNotInstalled() throws Exception {
        Path app = fakeMacApp(dir.resolve("Applications"), "v1");
        Path build = fakeMacApp(dir.resolve("build"), "v2");
        Files.writeString(build.resolve("Contents/MacOS/InfraDesk"), "#!/bin/sh\nexit 3\n");
        Path zipFile = dir.resolve("InfraDesk-beta-macOS.zip");
        assertEquals(0, new ProcessBuilder("ditto", "-c", "-k", "--keepParent", build.toString(), zipFile.toString()).start().waitFor());
        byte[] zip = Files.readAllBytes(zipFile);
        String base = serve(zip, sha256(zip) + "  InfraDesk-beta-macOS.zip\n");
        UpdateInstaller installer = new UpdateInstaller(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
                UpdateInstaller.Platform.MAC, app, dir.resolve("work"), dir.resolve("logs").resolve("swap.log"), true);
        var release = new UpdateService.Release(9, "", base, base, base + "/dl/InfraDesk-beta-macOS.zip", base + "/sums");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> installer.prepare(release, (d, t) -> { }, () -> false));
        assertTrue(e.getMessage().contains("자가 점검"), e.getMessage());
        assertFalse(Files.exists(app.resolveSibling(UpdateInstaller.STAGING)));
        assertEquals("v1", Files.readString(app.resolve("Contents/MacOS/version.txt")));
    }

    // ---- helpers ----

    /** Runs the swap script against a stand-in process and checks the result. */
    private void assertSwapped(UpdateInstaller installer, UpdateInstaller.Prepared prepared, Path app, Path marker)
            throws Exception {
        Process standIn = new ProcessBuilder(OS.WINDOWS.isCurrentOs()
                ? new String[]{"powershell.exe", "-NoProfile", "-Command", "Start-Sleep -Seconds 1"}
                : new String[]{"sleep", "1"}).start();
        Process swap = installer.startSwap(prepared, standIn.pid(), false);
        assertTrue(swap.waitFor(60, TimeUnit.SECONDS), "swap finished");
        String log = Files.readString(dir.resolve("logs").resolve("swap.log"));
        assertTrue(log.contains("updated"), log);
        assertEquals("v2", Files.readString(marker).strip());
        assertFalse(Files.exists(app.resolveSibling(app.getFileName() + ".previous")), "backup removed");
        assertFalse(Files.exists(app.resolveSibling(UpdateInstaller.STAGING)), "staging removed");
    }

    private static Path fakeMacApp(Path parent, String version) throws IOException {
        Path app = parent.resolve("InfraDesk.app");
        Path macOs = Files.createDirectories(app.resolve("Contents").resolve("MacOS"));
        Files.writeString(macOs.resolve("version.txt"), version);
        Path launcher = macOs.resolve("InfraDesk");
        Files.writeString(launcher, "#!/bin/sh\n[ \"$1\" = \"--self-test\" ] && echo \"SELF-TEST OK " + version + "\"\nexit 0\n");
        Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwxr-xr-x"));
        return app;
    }

    private static byte[] windowsAppZip(String version) throws IOException {
        var bytes = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry("InfraDesk/"));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("InfraDesk/InfraDesk.exe"));
            out.write(version.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("InfraDesk/runtime/release"));
            out.write("JAVA_VERSION=\"25\"".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return bytes.toByteArray();
    }

    /** /sums → checksums, /dl/<name> → 302 to /cdn/<name> → the archive. */
    private String serve(byte[] archive, String sums) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sums", ex -> send(ex, 200, sums.getBytes(StandardCharsets.UTF_8)));
        server.createContext("/dl/", ex -> {
            ex.getResponseHeaders().add("Location", ex.getRequestURI().getPath().replace("/dl/", "/cdn/"));
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/cdn/", ex -> send(ex, 200, archive));
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void send(com.sun.net.httpserver.HttpExchange ex, int status, byte[] body) throws IOException {
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
