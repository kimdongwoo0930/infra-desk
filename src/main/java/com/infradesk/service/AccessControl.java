package com.infradesk.service;

import com.infradesk.core.AccessMode;
import com.infradesk.storage.InMemorySecretStore;

import java.time.Clock;

/**
 * 읽기 / 관리 모드와 관리 비밀번호를 한데 묶은 것. 화면(MainFrame)은 이 하나만 받는다.
 */
public record AccessControl(AdminPasswordService passwords, AccessModeService mode) {

    /** 실제 앱: 항상 읽기 모드로 시작한다. */
    public static AccessControl of(AdminPasswordService passwords) {
        return new AccessControl(passwords, new AccessModeService(passwords));
    }

    /**
     * 시험과 스냅샷용: 비밀번호 없이 관리 모드로 시작한다(서버를 바꾸는 버튼이 모두 보인다). 앱 코드는 쓰지 않는다.
     */
    public static AccessControl unlocked() {
        AdminPasswordService passwords = new AdminPasswordService(new InMemorySecretStore(), Clock.systemUTC());
        return new AccessControl(passwords, new AccessModeService(passwords, AccessMode.MANAGE));
    }
}
