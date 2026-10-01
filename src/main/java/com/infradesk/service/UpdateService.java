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
 * 롤링 "beta" GitHub 릴리스에 더 새로운 빌드가 있는지 확인하고, 자동으로 확인할지 여부를
 * 저장한다({@code <configDir>/app-settings.json}). 공개 GitHub API에 단순 GET 요청만 보내고
 * 다른 것은 보내지 않는다. 블로킹이므로 EDT 밖에서 호출한다.
 */
public class UpdateService {

    /**
     * 공개된 최신 베타.
     *
     * @param downloadUrl  이 OS에서 사람이 내려받는 것(macOS는 dmg) 또는 릴리스 페이지
     * @param updateUrl    앱이 스스로 설치할 때 쓰는 아카이브. 릴리스에 없으면 null
     * @param checksumsUrl 릴리스의 {@code SHA256SUMS.txt}. 릴리스에 없으면 null
     */
    public record Release(int build, String commit, String pageUrl, String downloadUrl, String updateUrl,
                          String checksumsUrl) {
    }

    public static final String CHECKSUMS_ASSET = "SHA256SUMS.txt";

    public static final URI BETA_RELEASE_API =
            URI.create("https://api.github.com/repos/kimdongwoo0930/infra-desk/releases/tags/beta");
    private static final Pattern BUILD = Pattern.compile("빌드 #?(\\d+)");
    private static final Pattern COMMIT = Pattern.compile("커밋 \\[`([0-9a-f]{7,40})`]");
    private static final Logger LOG = Logger.getLogger(UpdateService.class.getName());

    private final HttpClient http;
    private final URI releaseApi;
    private final String assetName;
    private final String updateAssetName;
    private final Path settingsFile;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * @param settingsFile 자동 확인 설정이 저장되는 파일. null이면 메모리에만 둔다(데모)
     * @param assetName    이 OS용 다운로드. 예: "InfraDesk-beta-macOS.dmg"
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

    /** 실행 중인 OS용 베타 다운로드의 에셋 이름. */
    public static String assetForCurrentOs() {
        return windows() ? "InfraDesk-beta-windows.zip" : "InfraDesk-beta-macOS.dmg";
    }

    /** 앱이 스스로 설치할 때 쓰는 에셋: 앱을 담은 zip(macOS는 .app 번들). */
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

    /** 최신 베타 릴리스를 가져온다. 실패하면 한국어 메시지와 함께 예외를 던진다. */
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

    /** 테스트를 위해 package-private. */
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
        return new Release(Integer.parseInt(b.group(1)), c.find() ? c.group(1) : "", page, download, update, checksums);
    }
}
