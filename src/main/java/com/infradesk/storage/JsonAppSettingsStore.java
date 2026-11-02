package com.infradesk.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infradesk.core.AppSettings;
import com.infradesk.core.OverviewView;
import com.infradesk.core.ServerGrouping;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;

/**
 * {@code <configDir>/app-settings.json}에 설정 "일반"의 값을 저장한다.
 *
 * <p>이 파일은 {@code UpdateService}의 {@code autoUpdateCheck}도 담는다. 그래서 저장할 때 파일 전체를 새로 쓰지 않고,
 * 읽어 온 내용에서 이 클래스가 아는 칸({@code startServerId}, {@code grouping}, {@code overviewView})만 바꿔서 다시 쓴다. 모르는 칸은 그대로 남는다.
 */
public class JsonAppSettingsStore implements AppSettingsStore {

    private static final Logger LOG = Logger.getLogger(JsonAppSettingsStore.class.getName());

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public JsonAppSettingsStore(Path configDir) {
        this.file = configDir.resolve("app-settings.json");
    }

    @Override
    public synchronized AppSettings load() {
        ObjectNode root = readRoot();
        JsonNode start = root.get("startServerId");
        JsonNode grouping = root.get("grouping");
        JsonNode view = root.get("overviewView");
        return new AppSettings(
                start == null || !start.isTextual() ? null : start.asText(),
                ServerGrouping.fromName(grouping == null ? null : grouping.asText(), ServerGrouping.ACCOUNT),
                OverviewView.fromName(view == null ? null : view.asText(), OverviewView.CARDS));
    }

    @Override
    public synchronized void save(AppSettings settings) {
        ObjectNode root = readRoot();
        if (settings.startServerId() == null) {
            root.remove("startServerId");
        } else {
            root.put("startServerId", settings.startServerId());
        }
        root.put("grouping", settings.grouping().name());
        root.put("overviewView", settings.overviewView().name());
        try {
            Files.createDirectories(file.getParent());
            // 임시 파일에 쓰고 바꿔치기한다: 쓰다가 멈춰도 원래 파일이 반쯤 쓰인 채 남지 않는다.
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), root);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StorageException("앱 설정 파일을 저장하지 못했어요: " + file, e);
        }
    }

    /** 파일의 내용. 파일이 없거나 깨졌으면 빈 객체(시작을 막지 않는다). */
    private ObjectNode readRoot() {
        if (!Files.exists(file)) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode node = mapper.readTree(file.toFile());
            if (node instanceof ObjectNode object) {
                return object;
            }
        } catch (IOException e) {
            LOG.warning("Could not read " + file + ": " + e.getMessage());
        }
        return mapper.createObjectNode();
    }
}
