package com.infradesk.service;

import com.infradesk.core.AccessMode;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 지금 앱이 읽기 모드인지 관리 모드인지. 앱을 켤 때는 항상 읽기 모드이고(저장하지 않는다), 관리 모드로 바꿀 때만 관리 비밀번호를
 * 확인한다. 읽기 모드로 돌아오는 데는 비밀번호가 필요 없다.
 *
 * <p>화면은 {@link #onChange}로 모드가 바뀌는 것을 듣고, 서버를 바꾸는 버튼을 보이거나 숨긴다. 서버를 바꾸는 동작을 받는 쪽도
 * {@link #isReadOnly}로 한 번 더 막아서, 화면에서 숨기는 것을 놓쳐도 동작은 실행되지 않는다.
 */
public final class AccessModeService {

    private final AdminPasswordService passwords;
    private final List<Consumer<AccessMode>> listeners = new CopyOnWriteArrayList<>();
    private volatile AccessMode mode;

    /** 읽기 모드로 시작한다. */
    public AccessModeService(AdminPasswordService passwords) {
        this(passwords, AccessMode.READ_ONLY);
    }

    /** 시험과 개발용: 시작 모드를 정한다. 실제 앱은 항상 읽기 모드로 시작한다. */
    public AccessModeService(AdminPasswordService passwords, AccessMode start) {
        this.passwords = passwords;
        this.mode = start;
    }

    public AccessMode mode() {
        return mode;
    }

    public boolean isReadOnly() {
        return mode == AccessMode.READ_ONLY;
    }

    /**
     * 관리 모드로 바꾼다. 비밀번호가 맞을 때만 바뀐다.
     *
     * @return 확인 결과({@link AdminPasswordService.Check#OK}일 때만 바뀌었다)
     */
    public AdminPasswordService.Check switchToManage(char[] password) {
        AdminPasswordService.Check check = passwords.verify(password);
        if (check == AdminPasswordService.Check.OK) {
            set(AccessMode.MANAGE);
        }
        return check;
    }

    /** 읽기 모드로 돌아간다(비밀번호 없이). */
    public void switchToReadOnly() {
        set(AccessMode.READ_ONLY);
    }

    /** 모드가 바뀔 때마다 불린다. 바꾼 스레드에서 불리므로 화면을 건드리는 쪽은 EDT에서 처리해야 한다. */
    public void onChange(Consumer<AccessMode> listener) {
        listeners.add(listener);
    }

    private synchronized void set(AccessMode next) {
        if (next != mode) {
            mode = next;
            listeners.forEach(l -> l.accept(next));
        }
    }
}
