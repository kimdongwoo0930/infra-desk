package com.infradesk.storage;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.infradesk.alert.AlertSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 알림 설정을 {@code <configDir>/alerts.json}에 저장한다. */
public class JsonAlertSettingsStore implements AlertSettingsStore {

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public JsonAlertSettingsStore(Path configDir) {
        this.file = configDir.resolve("alerts.json");
    }

    @Override
    public synchronized AlertSettings load() {
        if (!Files.exists(file)) {
            return AlertSettings.DEFAULT;
        }
        try {
            return mapper.readValue(file.toFile(), AlertSettings.class);
        } catch (IOException e) {
            throw new StorageException("알림 설정 파일을 읽지 못했어요: " + file, e);
        }
    }

    @Override
    public synchronized void save(AlertSettings settings) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), settings);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StorageException("알림 설정을 저장하지 못했어요: " + file, e);
        }
    }
}
