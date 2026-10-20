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
 * 모든 비밀값을 AES-256-GCM으로 암호화한 파일 하나({@code <configDir>/secrets.vault})에 저장한다.
 * 256비트 키만 OS 키체인에 항목 하나로 둔다. 키체인은 실행당 최대 한 번 읽고,
 * 복호화한 값은 메모리에만 둔다.
 *
 * <p>이유: 비밀값마다 키체인 항목을 만들면 패키징된(ad-hoc 서명) 앱에서 거의 클릭마다 macOS가
 * 로그인 비밀번호를 물었다. "항상 허용"은 항목별로 적용되고, 서명되지 않은 앱에서는 기억이
 * 안정적이지 않기 때문이다.
 *
 * <p>파일 형식: {@code "IDV1"} 매직 값, 12바이트 IV, JSON 맵의 AES-GCM 암호문(128비트 태그).
 * 금고에 없는 비밀값은 {@code legacy}(이전 항목별 키체인 저장소)에서 한 번 찾아 복사해 넣는다.
 */
public class VaultSecretStore implements SecretStore {

    static final String MASTER_KEY_ITEM = "vault.masterKey";
    /** 모든 이전 항목을 복사하면 설정한다. 이후에는 이전 저장소를 확인하지 않는다. */
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
    /** 이번 실행에서 이전 저장소를 이미 찾아본 이름(찾았든 못 찾았든). 한 번만 물어보기 위한 것이다. */
    private final java.util.Set<String> legacyChecked = new java.util.HashSet<>();

    /**
     * @param keychain 마스터 키를 보관하는 곳(항목 하나)
     * @param legacy   이전하려는 옛 항목별 저장소. 없으면 null
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
     * 주어진 이름을 이전 저장소에서 금고로 복사하고, 이전 저장소에서는 삭제한 뒤,
     * 금고를 이전 완료로 표시한다. 이전 항목을 만든 프로세스(개발 JVM)에서 실행해야
     * macOS가 항목마다 묻지 않는다.
     *
     * @return 복사한 비밀값 개수
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
