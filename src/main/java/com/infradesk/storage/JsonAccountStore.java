package com.infradesk.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.infradesk.core.Account;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

/** Stores accounts as JSON in {@code <configDir>/accounts.json}. Writes atomically via a temp file. */
public class JsonAccountStore implements AccountStore {

    private static final TypeReference<List<Account>> ACCOUNTS = new TypeReference<>() {
    };

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public JsonAccountStore(Path configDir) {
        this.file = configDir.resolve("accounts.json");
    }

    @Override
    public List<Account> load() {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            return List.copyOf(mapper.readValue(file.toFile(), ACCOUNTS));
        } catch (IOException e) {
            throw new StorageException("계정 설정 파일을 읽지 못했어요: " + file, e);
        }
    }

    @Override
    public void save(List<Account> accounts) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerFor(ACCOUNTS).writeValue(tmp.toFile(), accounts);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictToOwner(file);
        } catch (IOException e) {
            throw new StorageException("계정 설정 파일을 저장하지 못했어요: " + file, e);
        }
    }

    /** The file holds OCIDs; keep it readable only by the current user where the OS supports it. */
    private static void restrictToOwner(Path path) throws IOException {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        }
    }
}
