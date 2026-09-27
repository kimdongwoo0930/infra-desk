package com.infradesk.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.infradesk.ssh.SavedCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Stores saved commands in {@code <configDir>/commands.json}. */
public class JsonSavedCommandStore implements SavedCommandStore {

    private static final TypeReference<List<SavedCommand>> TYPE = new TypeReference<>() {
    };

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public JsonSavedCommandStore(Path configDir) {
        this.file = configDir.resolve("commands.json");
    }

    @Override
    public synchronized List<SavedCommand> load() {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            return List.copyOf(mapper.readValue(file.toFile(), TYPE));
        } catch (IOException e) {
            throw new StorageException("저장된 명령어 파일을 읽지 못했어요: " + file, e);
        }
    }

    @Override
    public synchronized void save(List<SavedCommand> commands) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerFor(TYPE).writeValue(tmp.toFile(), commands);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StorageException("저장된 명령어를 저장하지 못했어요: " + file, e);
        }
    }
}
