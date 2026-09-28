package com.infradesk.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installs a beta build over the running app:
 * <ol>
 *   <li>downloads the release's app archive and checks it against the release's {@code SHA256SUMS.txt};</li>
 *   <li>unpacks it into a staging folder next to the installed app (same volume, so the swap is a rename);</li>
 *   <li>runs the new app's {@code --self-test}, so a broken build never replaces a working one;</li>
 *   <li>{@link #startSwap} hands over to a small script that waits for this process to exit, swaps the
 *       folders (putting the old one back if anything fails) and starts the new version.</li>
 * </ol>
 * Files the app downloads itself carry no quarantine flag, so macOS opens the new build without the
 * {@code xattr} step a browser download needs. Blocking; call off the EDT.
 */
public class UpdateInstaller {

    public enum Platform {
        MAC, WINDOWS
    }

    /** A verified, unpacked and self-tested update waiting for {@link #startSwap}. */
    public record Prepared(UpdateService.Release release, Path newApp) {
    }

    /** Download progress; {@code total} is -1 when the server doesn't say. Called off the EDT. */
    public interface Progress {
        void bytes(long done, long total);
    }

    static final String STAGING = ".infradesk-update";
    private static final Logger LOG = Logger.getLogger(UpdateInstaller.class.getName());

    private final HttpClient http;
    private final Platform platform;
    private final Path installedApp;
    private final Path workDir;
    private final Path logFile;
    private final boolean selfTest;

    /**
     * @param installedApp the .app bundle (macOS) or the folder holding InfraDesk.exe (Windows)
     * @param workDir      where the archive and the swap script go
     * @param logFile      where the swap script writes what it did
     * @param selfTest     run the new app's --self-test before accepting it (off only in tests)
     */
    public UpdateInstaller(HttpClient http, Platform platform, Path installedApp, Path workDir, Path logFile,
                           boolean selfTest) {
        this.http = http;
        this.platform = platform;
        this.installedApp = installedApp;
        this.workDir = workDir;
        this.logFile = logFile;
        this.selfTest = selfTest;
    }

    /** For the running packaged app; empty when running from Gradle or on another OS. */
    public static Optional<UpdateInstaller> forRunningApp(Path workDir, Path logFile) {
        String appPath = System.getProperty("jpackage.app-path");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Platform platform = os.contains("mac") ? Platform.MAC : os.contains("win") ? Platform.WINDOWS : null;
        if (appPath == null || platform == null) {
            return Optional.empty();
        }
        return installedApp(platform, Path.of(appPath).toAbsolutePath()).map(app -> new UpdateInstaller(
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL) // GitHub assets redirect to a CDN
                        .connectTimeout(Duration.ofSeconds(15))
                        .build(),
                platform, app, workDir, logFile, true));
    }

    /** .../InfraDesk.app/Contents/MacOS/InfraDesk → the bundle; ...\InfraDesk\InfraDesk.exe → its folder. */
    static Optional<Path> installedApp(Platform platform, Path launcher) {
        if (platform == Platform.MAC) {
            Path macOs = launcher.getParent();
            Path contents = macOs == null ? null : macOs.getParent();
            Path bundle = contents == null ? null : contents.getParent();
            return bundle != null && bundle.getFileName().toString().endsWith(".app") ? Optional.of(bundle) : Optional.empty();
        }
        return Optional.ofNullable(launcher.getParent());
    }

    public Path installedApp() {
        return installedApp;
    }

    /** Why this release can't be installed in place (a Korean sentence), or empty when it can. */
    public Optional<String> blocker(UpdateService.Release release) {
        if (release.updateUrl() == null || release.checksumsUrl() == null) {
            return Optional.of("이 빌드에는 자동 설치용 파일이 없어요.");
        }
        String path = installedApp.toString();
        if (path.contains("/AppTranslocation/") || path.startsWith("/Volumes/")) {
            return Optional.of("디스크 이미지나 임시 위치에서 실행 중이에요. 응용 프로그램 폴더로 옮긴 뒤 다시 시도하세요.");
        }
        Path parent = installedApp.getParent();
        if (parent == null || !Files.isWritable(parent) || !Files.isWritable(installedApp)) {
            return Optional.of("앱이 있는 폴더(" + parent + ")에 쓸 권한이 없어요.");
        }
        return Optional.empty();
    }

    /** Downloads, verifies, unpacks and self-tests the update. Throws with a Korean message on failure. */
    public Prepared prepare(UpdateService.Release release, Progress progress, BooleanSupplier cancelled) {
        blocker(release).ifPresent(reason -> {
            throw new IllegalStateException(reason);
        });
        try {
            Files.createDirectories(workDir);
            String assetName = lastSegment(release.updateUrl());
            String expected = expectedHash(fetchText(release.checksumsUrl()), assetName)
                    .orElseThrow(() -> new IllegalStateException("릴리스의 SHA256SUMS.txt에 " + assetName + "이(가) 없어요."));

            Path archive = workDir.resolve("InfraDesk-update-" + release.build() + ".zip");
            String actual = download(release.updateUrl(), archive, progress, cancelled);
            if (!actual.equalsIgnoreCase(expected)) {
                Files.deleteIfExists(archive);
                throw new IllegalStateException("받은 파일의 SHA-256이 릴리스에 적힌 값과 달라요. 다시 시도해 주세요.");
            }
            LOG.info(() -> "Update build " + release.build() + " downloaded and verified (" + actual + ")");

            Path staging = installedApp.resolveSibling(STAGING);
            deleteTree(staging);
            Files.createDirectories(staging);
            if (platform == Platform.WINDOWS) {
                try {
                    Files.setAttribute(staging, "dos:hidden", true); // a leading dot doesn't hide it there
                } catch (IOException | UnsupportedOperationException ignored) {
                    // Cosmetic only.
                }
            }
            extract(archive, staging);
            Files.deleteIfExists(archive);
            Path newApp = staging.resolve(platform == Platform.MAC ? "InfraDesk.app" : "InfraDesk");
            if (!Files.isRegularFile(launcher(newApp))) {
                deleteTree(staging);
                throw new IllegalStateException("받은 파일에 InfraDesk 앱이 들어 있지 않아요.");
            }
            if (selfTest) {
                runSelfTest(newApp);
            }
            return new Prepared(release, newApp);
        } catch (IOException e) {
            throw new IllegalStateException("업데이트를 준비하지 못했어요: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("중단됐어요");
        }
    }

    /**
     * Starts the script that swaps in {@code prepared} once this process exits, then relaunches.
     * The caller must quit right after. Returns the script process (tests wait on it).
     */
    public Process startSwap(Prepared prepared) {
        return startSwap(prepared, ProcessHandle.current().pid(), true);
    }

    /** @param waitFor pid to wait for; @param launch start the app afterwards (off in tests) */
    Process startSwap(Prepared prepared, long waitFor, boolean launch) {
        try {
            Files.createDirectories(workDir);
            Files.createDirectories(logFile.getParent());
            String name = platform == Platform.MAC ? "update-swap.sh" : "update-swap.ps1";
            Path script = workDir.resolve(name);
            try (InputStream in = UpdateInstaller.class.getResourceAsStream(name)) {
                if (in == null) {
                    throw new IllegalStateException(name + " is missing from the app");
                }
                Files.copy(in, script, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            String pid = Long.toString(waitFor);
            List<String> command = platform == Platform.MAC
                    ? List.of("/bin/sh", script.toString(), pid, installedApp.toString(), prepared.newApp().toString())
                    : List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                            "-WindowStyle", "Hidden", "-File", script.toString(),
                            pid, installedApp.toString(), prepared.newApp().toString());
            LOG.info(() -> "Starting update swap to build " + prepared.release().build() + "; log: " + logFile);
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            if (!launch) {
                builder.environment().put("INFRADESK_SWAP_NO_LAUNCH", "1");
            }
            return builder.start();
        } catch (IOException e) {
            throw new IllegalStateException("업데이트 설치를 시작하지 못했어요: " + e.getMessage(), e);
        }
    }

    /** Removes a staged update that was never swapped in (cancelled, or the app was quit another way). */
    public void discard(Prepared prepared) {
        deleteTree(prepared.newApp().getParent());
    }

    // ---- steps ----

    private String fetchText(String url) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request(url), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " (" + lastSegment(url) + ")");
        }
        return response.body();
    }

    /** Streams to {@code target} and returns the SHA-256 of what was written. */
    private String download(String url, Path target, Progress progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        HttpResponse<InputStream> response = http.send(request(url), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("HTTP " + response.statusCode() + " (" + lastSegment(url) + ")");
        }
        long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        MessageDigest sha = sha256();
        long done = 0;
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(target)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (cancelled.getAsBoolean()) {
                    throw new CancellationException("취소했어요");
                }
                out.write(buf, 0, n);
                sha.update(buf, 0, n);
                done += n;
                progress.bytes(done, total);
            }
        } catch (CancellationException | IOException e) {
            Files.deleteIfExists(target);
            throw e;
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private void extract(Path archive, Path dest) throws IOException, InterruptedException {
        if (platform == Platform.MAC) {
            // ditto keeps what a .app needs: symlinks, exec bits and the code signature.
            Process p = new ProcessBuilder("ditto", "-x", "-k", archive.toString(), dest.toString())
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (p.waitFor() != 0) {
                throw new IOException("압축을 풀지 못했어요 (" + out + ")");
            }
        } else {
            unzip(archive, dest);
        }
    }

    private void runSelfTest(Path newApp) throws IOException, InterruptedException {
        Path out = workDir.resolve("update-self-test.log");
        Process p = new ProcessBuilder(launcher(newApp).toString(), "--self-test")
                .redirectErrorStream(true)
                .redirectOutput(out.toFile())
                .start();
        if (!p.waitFor(2, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            deleteTree(newApp.getParent());
            throw new IllegalStateException("새 빌드의 자가 점검이 끝나지 않았어요. 설치하지 않았어요.");
        }
        if (p.exitValue() != 0) {
            deleteTree(newApp.getParent());
            throw new IllegalStateException("새 빌드가 자가 점검을 통과하지 못해서 설치하지 않았어요. 기록: " + out);
        }
    }

    private Path launcher(Path app) {
        return platform == Platform.MAC
                ? app.resolve("Contents").resolve("MacOS").resolve("InfraDesk")
                : app.resolve("InfraDesk.exe");
    }

    // ---- helpers (package-private for tests) ----

    /** The hash for {@code fileName} in {@code sha256sum} output ("hex  name" or "hex *name"). */
    static Optional<String> expectedHash(String sums, String fileName) {
        for (String line : sums.split("\\R")) {
            String[] parts = line.strip().split("\\s+", 2);
            if (parts.length == 2 && parts[0].matches("[0-9a-fA-F]{64}")) {
                String name = parts[1].startsWith("*") ? parts[1].substring(1) : parts[1];
                if (name.equals(fileName)) {
                    return Optional.of(parts[0].toLowerCase(Locale.ROOT));
                }
            }
        }
        return Optional.empty();
    }

    /** Plain unzip that refuses entries escaping {@code dest} ("zip slip"). */
    static void unzip(Path archive, Path dest) throws IOException {
        Path root = dest.toAbsolutePath().normalize();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException("잘못된 압축 파일이에요 (" + entry.getName() + ")");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    static String lastSegment(String url) {
        String path = URI.create(url).getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "InfraDesk")
                .GET().build();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            List<Path> all = new ArrayList<>(paths.sorted(Comparator.reverseOrder()).toList());
            for (Path p : all) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
