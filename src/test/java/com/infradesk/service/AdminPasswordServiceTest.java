package com.infradesk.service;

import com.infradesk.service.AdminPasswordService.Check;
import com.infradesk.storage.InMemorySecretStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminPasswordServiceTest {

    /** 손으로 움직이는 시계(잠금 시간을 기다리지 않고 시험한다). */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final InMemorySecretStore secrets = new InMemorySecretStore();
    private final MutableClock clock = new MutableClock();
    private final AdminPasswordService service = new AdminPasswordService(secrets, clock);

    private static char[] pw(String s) {
        return s.toCharArray();
    }

    // ── 설정과 확인 ─────────────────────────────────────────────

    @Test
    void thereIsNoPasswordAtFirst() {
        assertFalse(service.isSet());
        assertEquals(Check.NOT_SET, service.verify(pw("abcd")));
    }

    @Test
    void aPasswordThatWasSetIsAccepted() {
        service.set(pw("hunter22"));

        assertTrue(service.isSet());
        assertEquals(Check.OK, service.verify(pw("hunter22")));
    }

    @Test
    void aWrongPasswordIsRejected() {
        service.set(pw("hunter22"));

        assertEquals(Check.WRONG, service.verify(pw("hunter23")));
        assertEquals(Check.WRONG, service.verify(pw("")));
    }

    @Test
    void theMinimumLengthIsFourCharacters() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.set(pw("abc")));
        assertTrue(e.getMessage().contains("4자"));
        assertFalse(service.isSet());

        service.set(pw("abcd"));
        assertTrue(service.isSet());
    }

    @Test
    void aSecondSetIsRefusedBecauseChangingNeedsTheCurrentPassword() {
        service.set(pw("hunter22"));

        assertThrows(IllegalStateException.class, () -> service.set(pw("another1")));
        assertEquals(Check.OK, service.verify(pw("hunter22")));
    }

    // ── 저장 ────────────────────────────────────────────────────

    @Test
    void thePasswordItselfIsNeverStoredOnlyASaltedHash() {
        service.set(pw("hunter22"));

        String stored = secrets.get(AdminPasswordService.KEY).orElseThrow();

        assertTrue(stored.startsWith("pbkdf2-sha256$"));
        assertFalse(stored.contains("hunter22"));
        assertEquals(4, stored.split("\\$").length, "형식: 방식$반복$소금$해시");
    }

    @Test
    void theSameLetterPasswordGetsADifferentHashEachTimeBecauseOfTheSalt() {
        service.set(pw("hunter22"));
        String first = secrets.get(AdminPasswordService.KEY).orElseThrow();
        service.reset();
        service.set(pw("hunter22"));
        String second = secrets.get(AdminPasswordService.KEY).orElseThrow();

        assertNotEquals(first, second);
        assertEquals(Check.OK, service.verify(pw("hunter22")));
    }

    @Test
    void thePasswordArrayIsWipedAfterUse() {
        char[] typed = pw("hunter22");
        service.set(typed);
        assertArrayEquals(new char[8], typed, "받은 비밀번호는 쓰고 나서 지운다");

        char[] again = pw("hunter22");
        service.verify(again);
        assertArrayEquals(new char[8], again);
    }

    @Test
    void aCorruptStoredValueCountsAsNotSetSoYouAreNeverLockedOutForever() {
        secrets.put(AdminPasswordService.KEY, "깨진 값");

        assertFalse(service.isSet());
        service.set(pw("newpass1"));
        assertEquals(Check.OK, service.verify(pw("newpass1")));
    }

    // ── 잠금 ────────────────────────────────────────────────────

    @Test
    void afterFiveWrongTriesEvenTheRightPasswordIsRefusedForAWhile() {
        service.set(pw("hunter22"));
        for (int i = 0; i < AdminPasswordService.MAX_FAILURES; i++) {
            assertEquals(Check.WRONG, service.verify(pw("nope" + i)));
        }

        assertEquals(Check.LOCKED, service.verify(pw("hunter22")), "막는 동안은 맞는 값도 검사하지 않는다");
        assertTrue(service.lockedFor().compareTo(Duration.ZERO) > 0);
    }

    @Test
    void theLockEndsAfterThirtySeconds() {
        service.set(pw("hunter22"));
        for (int i = 0; i < AdminPasswordService.MAX_FAILURES; i++) {
            service.verify(pw("nope" + i));
        }

        clock.advance(Duration.ofSeconds(29));
        assertEquals(Check.LOCKED, service.verify(pw("hunter22")));

        clock.advance(Duration.ofSeconds(2));
        assertEquals(Check.OK, service.verify(pw("hunter22")));
        assertEquals(Duration.ZERO, service.lockedFor());
    }

    @Test
    void aRightPasswordStartsTheCountOver() {
        service.set(pw("hunter22"));
        for (int i = 0; i < AdminPasswordService.MAX_FAILURES - 1; i++) {
            service.verify(pw("nope" + i));
        }
        assertEquals(Check.OK, service.verify(pw("hunter22")));

        for (int i = 0; i < AdminPasswordService.MAX_FAILURES - 1; i++) {
            assertEquals(Check.WRONG, service.verify(pw("nope" + i)), "성공 뒤에는 다시 처음부터 센다");
        }
        assertEquals(Check.OK, service.verify(pw("hunter22")));
    }

    // ── 변경과 초기화 ───────────────────────────────────────────

    @Test
    void changingNeedsTheCurrentPasswordAndThenOnlyTheNewOneWorks() {
        service.set(pw("hunter22"));

        assertEquals(Check.OK, service.change(pw("hunter22"), pw("brandnew")));

        assertEquals(Check.OK, service.verify(pw("brandnew")));
        assertEquals(Check.WRONG, service.verify(pw("hunter22")));
    }

    @Test
    void aWrongCurrentPasswordChangesNothing() {
        service.set(pw("hunter22"));

        assertEquals(Check.WRONG, service.change(pw("wrong!!"), pw("brandnew")));

        assertEquals(Check.OK, service.verify(pw("hunter22")));
        assertEquals(Check.WRONG, service.verify(pw("brandnew")));
    }

    @Test
    void aTooShortNewPasswordIsRefusedAndTheOldOneStays() {
        service.set(pw("hunter22"));

        assertThrows(IllegalArgumentException.class, () -> service.change(pw("hunter22"), pw("ab")));

        assertEquals(Check.OK, service.verify(pw("hunter22")));
    }

    @Test
    void resetRemovesOnlyThePasswordAndLetsYouSetANewOne() {
        secrets.put("account.demo.privateKey", "비밀 키");
        service.set(pw("hunter22"));

        service.reset();

        assertFalse(service.isSet());
        assertEquals("비밀 키", secrets.get("account.demo.privateKey").orElseThrow(), "다른 비밀값은 그대로");
        service.set(pw("afterrst"));
        assertEquals(Check.OK, service.verify(pw("afterrst")));
    }

    @Test
    void resetAlsoClearsALock() {
        service.set(pw("hunter22"));
        for (int i = 0; i < AdminPasswordService.MAX_FAILURES; i++) {
            service.verify(pw("nope" + i));
        }

        service.reset();
        service.set(pw("afterrst"));

        assertEquals(Check.OK, service.verify(pw("afterrst")));
    }
}
