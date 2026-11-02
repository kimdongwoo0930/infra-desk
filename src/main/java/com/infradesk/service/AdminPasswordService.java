package com.infradesk.service;

import com.infradesk.storage.SecretStore;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.logging.Logger;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 관리 비밀번호: 읽기 모드에서 관리 모드로 바꿀 때 묻는다.
 *
 * <p><b>저장:</b> 비밀번호 자체가 아니라 소금값을 붙인 PBKDF2-HMAC-SHA256 해시({@code pbkdf2-sha256$반복$소금$해시})를 비밀값
 * 저장소(금고)에 넣는다. 해시는 되돌릴 수 없어서, 저장된 값으로는 원래 비밀번호를 알 수 없다. 입력값을 같은 소금과 반복으로
 * 해시해 비교하기만 한다. 금고가 이 값을 한 번 더 암호화한다.
 *
 * <p><b>추측 막기:</b> 연달아 {@value #MAX_FAILURES}번 틀리면 {@link #LOCK}동안 확인 자체를 하지 않는다(그동안은 맞는 값도
 * 받지 않으므로 추측이 소용없다). 앱을 다시 켜면 풀리므로 이 PC에 접근할 수 있는 사람을 막는 장치는 아니다: 실수와 어깨너머를
 * 막는 용도다.
 *
 * <p>비밀번호나 해시는 로그에 남기지 않는다. 받은 비밀번호 배열은 쓰고 나서 0으로 지운다.
 */
public final class AdminPasswordService {

    private static final Logger LOG = Logger.getLogger(AdminPasswordService.class.getName());

    /** 금고에서 해시를 찾는 키. */
    static final String KEY = "app.adminPassword";
    /** 비밀번호의 최소 길이. */
    public static final int MIN_LENGTH = 4;
    static final int ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    static final int MAX_FAILURES = 5;
    static final Duration LOCK = Duration.ofSeconds(30);
    private static final String PREFIX = "pbkdf2-sha256";

    /** 확인 결과. */
    public enum Check {
        OK, WRONG,
        /** 너무 많이 틀려서 잠시 막는 중(값을 검사하지 않았다). */
        LOCKED,
        /** 설정된 비밀번호가 없다. */
        NOT_SET
    }

    private final SecretStore store;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private int failures;
    private Instant lockedUntil = Instant.MIN;

    public AdminPasswordService(SecretStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** 비밀번호가 설정돼 있는지. 저장된 값이 깨졌으면 설정되지 않은 것으로 본다(다시 정할 수 있게, 영영 못 들어가는 일이 없게). */
    public boolean isSet() {
        return stored().isPresent();
    }

    /** 처음 비밀번호를 정한다. 이미 있으면 {@link #change}를 써야 한다. */
    public synchronized void set(char[] password) {
        try {
            if (isSet()) {
                throw new IllegalStateException("이미 비밀번호가 있어요. 바꾸려면 현재 비밀번호가 필요해요.");
            }
            requireValid(password);
            store.put(KEY, encode(password));
        } finally {
            wipe(password);
        }
    }

    /** 입력한 비밀번호가 맞는지. 틀린 횟수를 세고, 너무 많으면 잠시 막는다. */
    public synchronized Check verify(char[] password) {
        try {
            Optional<String[]> parts = stored();
            if (parts.isEmpty()) {
                return Check.NOT_SET;
            }
            if (clock.instant().isBefore(lockedUntil)) {
                return Check.LOCKED;
            }
            if (matches(password, parts.get())) {
                failures = 0;
                return Check.OK;
            }
            if (++failures >= MAX_FAILURES) {
                failures = 0;
                lockedUntil = clock.instant().plus(LOCK);
                LOG.warning("Admin password locked after repeated failures");
            }
            return Check.WRONG;
        } finally {
            wipe(password);
        }
    }

    /** 막혀 있다면 풀리기까지 남은 시간. 아니면 0. */
    public synchronized Duration lockedFor() {
        Duration left = Duration.between(clock.instant(), lockedUntil);
        return left.isNegative() ? Duration.ZERO : left;
    }

    /**
     * 비밀번호를 바꾼다. 현재 비밀번호가 맞아야 한다(틀리면 {@link #verify}와 같이 센다).
     *
     * @return 현재 비밀번호의 확인 결과. {@link Check#OK}일 때만 바뀌었다
     */
    public synchronized Check change(char[] current, char[] next) {
        try {
            Check check = verify(current);
            if (check == Check.OK) {
                requireValid(next);
                store.put(KEY, encode(next));
            }
            return check;
        } finally {
            wipe(next);
        }
    }

    /** 비밀번호만 지운다(다른 비밀값은 그대로). 실행 옵션 {@code --reset-admin-password}가 쓴다. */
    public synchronized void reset() {
        store.delete(KEY);
        failures = 0;
        lockedUntil = Instant.MIN;
    }

    // ── 해시 ────────────────────────────────────────────────────

    /** 새 비밀번호가 쓸 수 있는지(길이). 안 되면 한국어 이유가 담긴 예외. */
    public static void requireValid(char[] password) {
        if (password == null || password.length < MIN_LENGTH) {
            throw new IllegalArgumentException("비밀번호는 " + MIN_LENGTH + "자 이상이어야 해요.");
        }
    }

    private String encode(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = hash(password, salt, ITERATIONS);
        Base64.Encoder b64 = Base64.getEncoder();
        return PREFIX + "$" + ITERATIONS + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(hash);
    }

    private static boolean matches(char[] password, String[] parts) {
        byte[] salt = Base64.getDecoder().decode(parts[2]);
        byte[] expected = Base64.getDecoder().decode(parts[3]);
        byte[] actual = hash(password, salt, Integer.parseInt(parts[1]));
        return MessageDigest.isEqual(expected, actual);   // 걸리는 시간이 다르지 않게 비교한다
    }

    private static byte[] hash(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("비밀번호를 해시하지 못했어요", e);
        } finally {
            spec.clearPassword();
        }
    }

    /** 저장된 값을 읽어 나눈다. 없거나 모양이 이상하면 비어 있다. */
    private Optional<String[]> stored() {
        Optional<String> value = store.get(KEY);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        String[] parts = value.get().split("\\$");
        try {
            if (parts.length == 4 && PREFIX.equals(parts[0]) && Integer.parseInt(parts[1]) > 0) {
                Base64.getDecoder().decode(parts[2]);
                Base64.getDecoder().decode(parts[3]);
                return Optional.of(parts);
            }
        } catch (IllegalArgumentException e) {
            // 아래에서 깨진 값으로 처리
        }
        LOG.warning("Stored admin password is unreadable; treating it as not set");
        return Optional.empty();
    }

    private static void wipe(char[] password) {
        if (password != null) {
            Arrays.fill(password, '\0');
        }
    }
}
