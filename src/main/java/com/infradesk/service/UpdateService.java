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
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub의 최신 정식 릴리스(프리릴리스를 뺀 가장 최근 릴리스, {@code releases/latest})에 더 새로운 버전이 있는지
 * 확인하고, 자동으로 확인할지 여부를 저장한다({@code <configDir>/app-settings.json}). 공개 GitHub API에 단순 GET
 * 요청만 보내고 다른 것은 보내지 않는다. 블로킹이므로 EDT 밖에서 호출한다.
 *
 * <p>릴리스 규칙: 태그 {@code v1.0.0}, 제목 "InfraDesk 1.0.0", 에셋은 버전이 이름에 들어가지 않는다
 * ({@code InfraDesk-macOS.dmg}, {@code InfraDesk-macOS.zip}, {@code InfraDesk-windows.zip}, {@code SHA256SUMS.txt}).
 * 새 버전은 빌드 번호가 아니라 <b>버전</b>({@link #isNewer})으로 비교한다.
 */
public class UpdateService {

    /**
     * 공개된 최신 릴리스.
     *
     * @param build        릴리스 본문에 적힌 CI 빌드 번호. 없으면 0(안내와 로그에만 쓴다)
     * @param downloadUrl  이 OS에서 사람이 내려받는 것(macOS는 dmg) 또는 릴리스 페이지
     * @param updateUrl    앱이 스스로 설치할 때 쓰는 아카이브. 릴리스에 없으면 null
     * @param checksumsUrl 릴리스의 {@code SHA256SUMS.txt}. 릴리스에 없으면 null
     * @param version      릴리스의 앱 버전("1.0.0"). 태그에서 읽는다. 없으면 ""
     */
    public record Release(int build, String commit, String pageUrl, String downloadUrl, String updateUrl,
                          String checksumsUrl, String version) {

        public Release(int build, String commit, String pageUrl, String downloadUrl, String updateUrl,
                       String checksumsUrl) {
            this(build, commit, pageUrl, downloadUrl, updateUrl, checksumsUrl, "");
        }

        /** 안내 문구에 쓰는 이름. "InfraDesk 1.0.0", 버전을 모르면 "새 버전". */
        public String label() {
            return version.isEmpty() ? "새 버전" : "InfraDesk " + version;
        }
    }

    public static final String CHECKSUMS_ASSET = "SHA256SUMS.txt";

    /** GitHub의 "latest": 프리릴리스와 초안을 뺀 가장 최근 릴리스. */
    public static final URI RELEASE_API =
            URI.create("https://api.github.com/repos/kimdongwoo0930/infra-desk/releases/latest");
    private static final Pattern BUILD = Pattern.compile("빌드 #?(\\d+)");
    private static final Pattern TAG = Pattern.compile("v(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");
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
     * @param assetName    이 OS용 다운로드. 예: "InfraDesk-macOS.dmg"
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

    /** 실행 중인 OS용 다운로드의 에셋 이름(버전이 들어가지 않는다). */
    public static String assetForCurrentOs() {
        return windows() ? "InfraDesk-windows.zip" : "InfraDesk-macOS.dmg";
    }

    /** 앱이 스스로 설치할 때 쓰는 에셋: 앱을 담은 zip(macOS는 .app 번들). */
    public static String updateAssetForCurrentOs() {
        return windows() ? "InfraDesk-windows.zip" : "InfraDesk-macOS.zip";
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /**
     * {@code candidate}가 {@code current}보다 새 버전인지("1.0.1"이 "1.0.0"보다 새롭다). 숫자로 비교한다("1.0.10"은
     * "1.0.9"보다 새롭다). 어느 쪽이든 {@code x.y.z} 모양이 아니면 비교할 수 없으므로 false.
     */
    public static boolean isNewer(String candidate, String current) {
        int[] a = parseVersion(candidate);
        int[] b = parseVersion(current);
        if (a == null || b == null) {
            return false;
        }
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return a[i] > b[i];
            }
        }
        return false;
    }

    private static int[] parseVersion(String version) {
        if (version == null) {
            return null;
        }
        Matcher m = VERSION.matcher(version);
        if (!m.matches()) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
        } catch (NumberFormatException e) {
            return null;
        }
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

    /** 최신 정식 릴리스를 가져온다. 실패하면 한국어 메시지와 함께 예외를 던진다. */
    public Release latest() {
        HttpRequest request = HttpRequest.newBuilder(releaseApi)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "InfraDesk")
                .GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                throw new IllegalStateException("아직 배포된 릴리스가 없어요");
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

    /**
     * 릴리스를 읽는다. 프리릴리스·초안이거나 태그가 {@code v1.0.0} 모양이 아니면(옛 베타 릴리스 등) 정식 릴리스가
     * 아니므로 예외를 던진다. 테스트를 위해 package-private.
     */
    static Release parse(JsonNode json, String assetName, String updateAssetName) {
        Matcher tag = TAG.matcher(json.path("tag_name").asText(""));
        if (json.path("prerelease").asBoolean(false) || json.path("draft").asBoolean(false) || !tag.matches()) {
            throw new IllegalStateException("아직 배포된 릴리스가 없어요");
        }
        String body = json.path("body").asText("");
        Matcher b = BUILD.matcher(json.path("name").asText("") + " " + body);
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
        String version = tag.group(1) + "." + tag.group(2) + "." + tag.group(3);
        return new Release(b.find() ? Integer.parseInt(b.group(1)) : 0, c.find() ? c.group(1) : "", page, download,
                update, checksums, version);
    }
}
