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
 * 실행 중인 앱 위에 베타 빌드를 설치한다:
 * <ol>
 *   <li>릴리스의 앱 아카이브를 내려받아 릴리스의 {@code SHA256SUMS.txt}와 대조한다;</li>
 *   <li>설치된 앱 옆의 임시 폴더에 풀어 놓는다(같은 볼륨이므로 교체가 이름 변경으로 끝난다);</li>
 *   <li>새 앱의 {@code --self-test}를 실행해서, 고장 난 빌드가 멀쩡한 앱을 대체하지 못하게 한다;</li>
 *   <li>{@link #startSwap}이 작은 스크립트에 넘긴다. 스크립트는 이 프로세스가 끝나길 기다렸다가
 *       폴더를 교체하고(무언가 실패하면 이전 폴더를 되돌린다) 새 버전을 시작한다.</li>
 * </ol>
 * 앱이 직접 내려받은 파일에는 격리(quarantine) 플래그가 없으므로, macOS는 브라우저로 받은 파일에
 * 필요한 {@code xattr} 단계 없이 새 빌드를 연다. 블로킹이므로 EDT 밖에서 호출한다.
 */
public class UpdateInstaller {

    public enum Platform {
        MAC, WINDOWS
    }

    /** 검증, 압축 해제, 자가 점검을 마치고 {@link #startSwap}을 기다리는 업데이트. */
    public record Prepared(UpdateService.Release release, Path newApp) {
    }

    /** 다운로드 진행률. 서버가 알려주지 않으면 {@code total}은 -1이다. EDT 밖에서 호출된다. */
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
     * @param installedApp .app 번들(macOS) 또는 InfraDesk.exe가 있는 폴더(Windows)
     * @param workDir      아카이브와 교체 스크립트가 놓이는 곳
     * @param logFile      교체 스크립트가 한 일을 기록하는 파일
     * @param selfTest     받아들이기 전에 새 앱의 --self-test를 실행한다(테스트에서만 끈다)
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

    /** 실행 중인 패키징된 앱용. Gradle로 실행하거나 다른 OS이면 빈 값. */
    public static Optional<UpdateInstaller> forRunningApp(Path workDir, Path logFile) {
        String appPath = System.getProperty("jpackage.app-path");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Platform platform = os.contains("mac") ? Platform.MAC : os.contains("win") ? Platform.WINDOWS : null;
        if (appPath == null || platform == null) {
            return Optional.empty();
        }
        return installedApp(platform, Path.of(appPath).toAbsolutePath()).map(app -> new UpdateInstaller(
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL) // GitHub 에셋은 CDN으로 리디렉트된다
                        .connectTimeout(Duration.ofSeconds(15))
                        .build(),
                platform, app, workDir, logFile, true));
    }

    /** .../InfraDesk.app/Contents/MacOS/InfraDesk → 번들, ...\InfraDesk\InfraDesk.exe → 그 폴더. */
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

    /** 이 릴리스를 제자리에 설치할 수 없는 이유(한국어 문장). 설치할 수 있으면 빈 값. */
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

    /** 업데이트를 내려받고, 검증하고, 풀고, 자가 점검한다. 실패하면 한국어 메시지와 함께 예외를 던진다. */
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
                    Files.setAttribute(staging, "dos:hidden", true); // 앞에 점이 있어도 여기서는 숨겨지지 않는다
                } catch (IOException | UnsupportedOperationException ignored) {
                    // 보기 좋게 하려는 것일 뿐이다.
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
     * 이 프로세스가 끝나면 {@code prepared}로 교체하고 다시 실행하는 스크립트를 시작한다.
     * 호출한 쪽은 곧바로 종료해야 한다. 스크립트 프로세스를 돌려준다(테스트가 기다린다).
     */
    public Process startSwap(Prepared prepared) {
        return startSwap(prepared, ProcessHandle.current().pid(), true);
    }

    /** @param waitFor 기다릴 pid; @param launch 끝난 뒤 앱을 시작할지(테스트에서는 끔) */
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
            ProcessBuilder builder = swapProcess(command);
            if (!launch) {
                builder.environment().put("INFRADESK_SWAP_NO_LAUNCH", "1");
            }
            return builder.start();
        } catch (IOException e) {
            throw new IllegalStateException("업데이트 설치를 시작하지 못했어요: " + e.getMessage(), e);
        }
    }

    /**
     * 교체 스크립트는 현재 디렉터리가 앱 안에 있는 채로 실행하면 안 된다. Windows는 어떤 프로세스든
     * 그 안에 "들어가 있는" 폴더의 이름을 바꾸지 못하는데, 앱의 작업 디렉터리는 보통 앱 폴더다
     * (탐색기에서 더블클릭한 경우). 그 때문에 Windows에서 빌드 10이 그대로 남은 적이 있다.
     */
    ProcessBuilder swapProcess(List<String> command) {
        return new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
    }

    /**
     * 교체 스크립트가 최근에 포기했다면 그 마지막 메시지(이전 버전이 다시 시작되었다).
     * 업데이트가 실패했는데 아무 일도 없었던 것처럼 보이지 않도록 시작할 때 확인한다.
     */
    public static Optional<String> recentSwapFailure(Path logFile, java.time.Instant now) {
        try {
            if (!Files.isRegularFile(logFile)
                    || Files.getLastModifiedTime(logFile).toInstant().isBefore(now.minus(Duration.ofMinutes(10)))) {
                return Optional.empty();
            }
            List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0; i--) {
                String line = lines.get(i).strip();
                if (line.isEmpty()) {
                    continue;
                }
                return line.contains("skipped") || line.contains("restoring") || line.contains("did not exit")
                        ? Optional.of(line) : Optional.empty();
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            LOG.fine(() -> "Could not read " + logFile + ": " + e.getMessage());
        }
        return Optional.empty();
    }

    /** 교체되지 못한 준비된 업데이트(취소했거나 앱을 다른 방법으로 종료한 경우)를 지운다. */
    public void discard(Prepared prepared) {
        deleteTree(prepared.newApp().getParent());
    }

    // ---- 단계 ----

    private String fetchText(String url) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request(url), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " (" + lastSegment(url) + ")");
        }
        return response.body();
    }

    /** {@code target}으로 스트리밍하고, 쓴 내용의 SHA-256을 돌려준다. */
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
            // ditto는 .app에 필요한 것을 보존한다: 심볼릭 링크, 실행 비트, 코드 서명.
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

    // ---- 헬퍼 (테스트를 위해 package-private) ----

    /** {@code sha256sum} 출력에서 {@code fileName}의 해시("hex  name" 또는 "hex *name"). */
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

    /** {@code dest} 밖으로 나가는 항목("zip slip")은 거부하는 단순 unzip. */
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
