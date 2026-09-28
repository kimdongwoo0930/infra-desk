package com.infradesk.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * All secrets in one AES-256-GCM encrypted file ({@code <configDir>/secrets.vault}); only the
 * 256-bit key lives in the OS keychain, as a single item. The keychain is read at most once per
 * run and decrypted values stay in memory.
 *
 * <p>Why: with one keychain item per secret, macOS asked for the login password on almost every
 * click in the packaged (ad-hoc signed) app, since "Always Allow" is per item and not reliably
 * remembered for unsigned apps.
 *
 * <p>File format: {@code "IDV1"} magic, 12-byte IV, AES-GCM ciphertext of a JSON map (128-bit tag).
 * Secrets missing from the vault are looked up once in {@code legacy} (the old per-item keychain
 * store) and copied in.
 */
public class VaultSecretStore implements SecretStore {

    static final String MASTER_KEY_ITEM = "vault.masterKey";
    /** Set once every legacy item was copied; afterwards the legacy store is never consulted. */
    static final String MIGRATED_MARKER = "_vault.migrated";
    private static final byte[] MAGIC = {'I', 'D', 'V', '1'};
    private static final int IV_BYTES = 12;
    private static final TypeReference<HashMap<String, String>> MAP = new TypeReference<>() {
    };

    private final Path file;
    private final SecretStore keychain;
    private final SecretStore legacy;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    private byte[] key;
    private Map<String, String> values;
    /** Names already looked up in the legacy store this run (found or not), so it's asked once. */
    private final java.util.Set<String> legacyChecked = new java.util.HashSet<>();

    /**
     * @param keychain where the master key is kept (one item)
     * @param legacy   old per-item store to migrate from, or null
     */
    public VaultSecretStore(Path configDir, SecretStore keychain, SecretStore legacy) {
        this.file = configDir.resolve("secrets.vault");
        this.keychain = keychain;
        this.legacy = legacy;
    }

    @Override
    public synchronized Optional<String> get(String name) {
        load();
        String v = values.get(name);
        if (v == null && legacy != null && !values.containsKey(MIGRATED_MARKER) && legacyChecked.add(name)) {
            Optional<String> old = legacy.get(name);
            if (old.isPresent()) {
                values.put(name, old.get());
                write();
                return old;
            }
        }
        return Optional.ofNullable(v);
    }

    @Override
    public synchronized void put(String name, String value) {
        load();
        values.put(name, value);
        write();
    }

    @Override
    public synchronized void delete(String name) {
        load();
        if (values.remove(name) != null) {
            write();
        }
        if (legacy != null) {
            legacy.delete(name);
        }
    }

    /**
     * Copies the given names from the legacy store into the vault, deletes them from the legacy
     * store, and marks the vault as migrated. Run it from the process that created the legacy
     * items (the dev JVM) so macOS doesn't ask for each one.
     *
     * @return number of secrets copied
     */
    public synchronized int migrateFromLegacy(java.util.Collection<String> names) {
        load();
        int copied = 0;
        if (legacy != null) {
            for (String name : names) {
                Optional<String> old = legacy.get(name);
                if (old.isPresent()) {
                    values.putIfAbsent(name, old.get());
                    copied++;
                }
            }
        }
        values.put(MIGRATED_MARKER, "true");
        write();
        if (legacy != null) {
            for (String name : names) {
                legacy.delete(name);
            }
        }
        return copied;
    }

    private void load() {
        if (values != null) {
            return;
        }
        key = masterKey();
        if (!Files.exists(file)) {
            values = new HashMap<>();
            return;
        }
        try {
            values = new HashMap<>(decrypt(Files.readAllBytes(file)));
        } catch (IOException | GeneralSecurityException e) {
            throw new StorageException("암호화된 비밀값 파일을 읽지 못했어요. 키체인의 InfraDesk 마스터 키가 바뀌었을 수 있어요: " + file, e);
        }
    }

    private byte[] masterKey() {
        Optional<String> stored = keychain.get(MASTER_KEY_ITEM);
        if (stored.isPresent()) {
            return Base64.getDecoder().decode(stored.get());
        }
        if (Files.exists(file)) {
            throw new StorageException("키체인에서 InfraDesk 마스터 키를 찾지 못했어요. 키체인 접근을 허용했는지 확인하세요.", null);
        }
        byte[] fresh = new byte[32];
        random.nextBytes(fresh);
        keychain.put(MASTER_KEY_ITEM, Base64.getEncoder().encodeToString(fresh));
        return fresh;
    }

    private void write() {
        try {
            byte[] data = encrypt(values);
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, data);
            if (tmp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | GeneralSecurityException e) {
            throw new StorageException("비밀값을 저장하지 못했어요: " + file, e);
        }
    }

    private byte[] encrypt(Map<String, String> map) throws IOException, GeneralSecurityException {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        c.updateAAD(MAGIC);
        byte[] ct = c.doFinal(mapper.writeValueAsBytes(map));
        return ByteBuffer.allocate(MAGIC.length + IV_BYTES + ct.length).put(MAGIC).put(iv).put(ct).array();
    }

    private Map<String, String> decrypt(byte[] data) throws IOException, GeneralSecurityException {
        if (data.length < MAGIC.length + IV_BYTES + 16 || !java.util.Arrays.equals(data, 0, 4, MAGIC, 0, 4)) {
            throw new IOException("not a vault file");
        }
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, data, MAGIC.length, IV_BYTES));
        c.updateAAD(MAGIC);
        byte[] plain = c.doFinal(data, MAGIC.length + IV_BYTES, data.length - MAGIC.length - IV_BYTES);
        return mapper.readValue(plain, MAP);
    }
}
