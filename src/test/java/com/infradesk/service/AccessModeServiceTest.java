package com.infradesk.service;

import com.infradesk.core.AccessMode;
import com.infradesk.service.AdminPasswordService.Check;
import com.infradesk.storage.InMemorySecretStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessModeServiceTest {

    private final AdminPasswordService passwords = new AdminPasswordService(new InMemorySecretStore(), Clock.systemUTC());
    private final AccessModeService access = new AccessModeService(passwords);

    private static char[] pw(String s) {
        return s.toCharArray();
    }

    @Test
    void theAppAlwaysStartsReadOnly() {
        assertEquals(AccessMode.READ_ONLY, access.mode());
        assertTrue(access.isReadOnly());
    }

    @Test
    void theRightPasswordSwitchesToManageAndListenersHearIt() {
        passwords.set(pw("hunter22"));
        List<AccessMode> heard = new ArrayList<>();
        access.onChange(heard::add);

        assertEquals(Check.OK, access.switchToManage(pw("hunter22")));

        assertEquals(AccessMode.MANAGE, access.mode());
        assertFalse(access.isReadOnly());
        assertEquals(List.of(AccessMode.MANAGE), heard);
    }

    @Test
    void aWrongPasswordLeavesTheAppReadOnlyAndSaysNothing() {
        passwords.set(pw("hunter22"));
        List<AccessMode> heard = new ArrayList<>();
        access.onChange(heard::add);

        assertEquals(Check.WRONG, access.switchToManage(pw("nope")));

        assertTrue(access.isReadOnly());
        assertTrue(heard.isEmpty());
    }

    @Test
    void withoutAPasswordYouCannotSwitchToManage() {
        assertEquals(Check.NOT_SET, access.switchToManage(pw("anything")));

        assertTrue(access.isReadOnly(), "비밀번호가 없는데 관리 모드로 들어가면 안 된다");
    }

    @Test
    void goingBackToReadOnlyNeedsNoPassword() {
        passwords.set(pw("hunter22"));
        access.switchToManage(pw("hunter22"));
        List<AccessMode> heard = new ArrayList<>();
        access.onChange(heard::add);

        access.switchToReadOnly();

        assertTrue(access.isReadOnly());
        assertEquals(List.of(AccessMode.READ_ONLY), heard);
    }

    @Test
    void switchingToTheModeItAlreadyHasStaysQuiet() {
        List<AccessMode> heard = new ArrayList<>();
        access.onChange(heard::add);

        access.switchToReadOnly();

        assertTrue(heard.isEmpty());
    }

    @Test
    void whileLockedTheRightPasswordIsNotEnough() {
        passwords.set(pw("hunter22"));
        for (int i = 0; i < AdminPasswordService.MAX_FAILURES; i++) {
            access.switchToManage(pw("nope" + i));
        }

        assertEquals(Check.LOCKED, access.switchToManage(pw("hunter22")));

        assertTrue(access.isReadOnly());
    }

    @Test
    void theTestHelperStartsUnlockedSoExistingScreensKeepTheirButtons() {
        assertFalse(AccessControl.unlocked().mode().isReadOnly());
        assertTrue(AccessControl.of(passwords).mode().isReadOnly(), "실제 앱 쪽은 읽기로 시작");
    }
}
