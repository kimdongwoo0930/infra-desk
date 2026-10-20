package com.infradesk.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.infradesk.ssh.SshSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** SSH 설정을 서버 id를 키로 {@code <configDir>/ssh-settings.json}에 저장한다. */
public class JsonSshSettingsStore implements SshSettingsStore {

    private static final TypeReference<LinkedHashMap<String, SshSettings>> TYPE = new TypeReference<>() {
    };

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public JsonSshSettingsStore(Path configDir) {
        this.file = configDir.resolve("ssh-settings.json");
    }

    @Override
    public synchronized Optional<SshSettings> get(String serverId) {
        return Optional.ofNullable(read().get(serverId));
    }

    @Override
    public synchronized void put(SshSettings settings) {
        Map<String, SshSettings> all = read();
        all.put(settings.serverId(), settings);
        write(all);
    }

    @Override
    public synchronized void delete(String serverId) {
        Map<String, SshSettings> all = read();
        if (all.remove(serverId) != null) {
            write(all);
        }
    }

    private Map<String, SshSettings> read() {
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        try {
            return mapper.readValue(file.toFile(), TYPE);
        } catch (IOException e) {
            throw new StorageException("SSH 설정 파일을 읽지 못했어요: " + file, e);
        }
    }

    private void write(Map<String, SshSettings> all) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerFor(TYPE).writeValue(tmp.toFile(), all);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            }
        } catch (IOException e) {
            throw new StorageException("SSH 설정 파일을 저장하지 못했어요: " + file, e);
        }
    }
}
