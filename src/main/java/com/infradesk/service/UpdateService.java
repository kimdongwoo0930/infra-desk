package com.infradesk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the rolling "beta" GitHub release for a newer build, and stores whether to check
 * automatically ({@code <configDir>/app-settings.json}). Sends nothing but a plain GET to the public
 * GitHub API. Blocking; call off the EDT.
 */
public class UpdateService {

    /**
     * The latest published beta.
     *
     * @param downloadUrl  what a person downloads for this OS (dmg on macOS), or the release page
     * @param updateUrl    the archive the app installs itself from, or null when the release has none
     * @param checksumsUrl {@code SHA256SUMS.txt} of the release, or null when the release has none
     * @param version      app version from the release title ("1.1.6"), or "" when the title has none
     */
    public record Release(int build, String commit, String pageUrl, String downloadUrl, String updateUrl,
                          String checksumsUrl, String version) {

        public Release(int build, String commit, String pageUrl, String downloadUrl, String updateUrl,
                       String checksumsUrl) {
            this(build, commit, pageUrl, downloadUrl, updateUrl, checksumsUrl, "");
        }

        /** "InfraDesk 1.1.6 Beta" for messages; "베타 빌드 16" when the title carries no version. */
        public String label() {
            return version.isEmpty() ? "베타 빌드 " + build : "InfraDesk " + version + " Beta";
        }
    }

    public static final String CHECKSUMS_ASSET = "SHA256SUMS.txt";

    public static final URI BETA_RELEASE_API =
            URI.create("https://api.github.com/repos/kimdongwoo0930/infra-desk/releases/tags/beta");
    private static final Pattern BUILD = Pattern.compile("빌드 #?(\\d+)");
    private static final Pattern VERSION = Pattern.compile("(\\d+\\.\\d+\\.\\d+)");
    private static final Pattern COMMIT = Pattern.compile("커밋 \\[`([0-9a-f]{7,40})`]");
    private static final Logger LOG = Logger.getLogger(UpdateService.class.getName());

    private final HttpClient http;
    private final URI releaseApi;
    private final String assetName;
    private final String updateAssetName;
    private final Path settingsFile;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * @param settingsFile where the auto-check preference lives, or null to keep it in memory (demo)
     * @param assetName    download for this OS, e.g. "InfraDesk-beta-macOS.dmg"
     */
    public UpdateService(HttpClient http, URI releaseApi, String assetName, Path settingsFile) {
        this(http, releaseApi, assetName, updateAssetForCurrentOs(), settingsFile);
    }

    public UpdateService(HttpClient http, URI releaseApi, String assetName, String updateAssetName, Path settingsFile) {
        this.http = http;
        this.releaseApi = releaseApi;
        this.assetName = assetName;
        this.updateAssetName = updateAssetName;
        this.settingsFile = settingsFile;
    }

    /** Asset name of the beta download for the running OS. */
    public static String assetForCurrentOs() {
        return windows() ? "InfraDesk-beta-windows.zip" : "InfraDesk-beta-macOS.dmg";
    }

    /** Asset the app installs itself from: a zip of the app (the .app bundle on macOS). */
    public static String updateAssetForCurrentOs() {
        return windows() ? "InfraDesk-beta-windows.zip" : "InfraDesk-beta-macOS.zip";
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private volatile Boolean autoCheckInMemory;

    public boolean autoCheck() {
        if (settingsFile == null) {
            return autoCheckInMemory == null || autoCheckInMemory;
        }
        try {
            if (Files.exists(settingsFile)) {
                JsonNode n = mapper.readTree(settingsFile.toFile()).get("autoUpdateCheck");
                return n == null || n.asBoolean(true);
            }
        } catch (IOException e) {
            LOG.warning("Could not read " + settingsFile + ": " + e.getMessage());
        }
        return true;
    }

    public void setAutoCheck(boolean enabled) {
        if (settingsFile == null) {
            autoCheckInMemory = enabled;
            return;
        }
        try {
            Files.createDirectories(settingsFile.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(settingsFile.toFile(), Map.of("autoUpdateCheck", enabled));
        } catch (IOException e) {
            throw new IllegalStateException("설정을 저장하지 못했어요: " + e.getMessage(), e);
        }
    }

    /** Fetches the latest beta release. Throws with a Korean message on failure. */
    public Release latest() {
        HttpRequest request = HttpRequest.newBuilder(releaseApi)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "InfraDesk")
                .GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                throw new IllegalStateException("아직 배포된 베타가 없어요");
            }
            if (response.statusCode() != 200) {
                throw new IllegalStateException("업데이트 정보를 받지 못했어요 (HTTP " + response.statusCode() + ")");
            }
            return parse(mapper.readTree(response.body()), assetName, updateAssetName);
        } catch (IOException e) {
            throw new IllegalStateException("업데이트 서버에 연결하지 못했어요", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("중단됐어요", e);
        }
    }

    /** Package-private for tests. */
    static Release parse(JsonNode json, String assetName, String updateAssetName) {
        String name = json.path("name").asText("");
        String body = json.path("body").asText("");
        Matcher b = BUILD.matcher(name);
        if (!b.find()) {
            b = BUILD.matcher(body);
            if (!b.find()) {
                throw new IllegalStateException("릴리스에서 빌드 번호를 찾지 못했어요");
            }
        }
        Matcher c = COMMIT.matcher(body);
        String page = json.path("html_url").asText("");
        String download = page;
        String update = null;
        String checksums = null;
        for (JsonNode asset : json.path("assets")) {
            String assetFile = asset.path("name").asText();
            String url = asset.path("browser_download_url").asText(null);
            if (assetName.equals(assetFile) && url != null) {
                download = url;
            }
            if (updateAssetName.equals(assetFile)) {
                update = url;
            }
            if (CHECKSUMS_ASSET.equals(assetFile)) {
                checksums = url;
            }
        }
        Matcher v = VERSION.matcher(name);
        return new Release(Integer.parseInt(b.group(1)), c.find() ? c.group(1) : "", page, download, update, checksums,
                v.find() ? v.group(1) : "");
    }
}
