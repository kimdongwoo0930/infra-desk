package com.infradesk.service;

import com.infradesk.core.AppSettings;
import com.infradesk.storage.AppSettingsStore;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * 화면이 쓰는 앱 설정 창구. 화면은 저장소({@code storage})를 직접 만지지 않고 이 클래스로 읽고 바꾼다.
 * 값이 바뀌면 등록된 듣는 쪽에 알려서, 설정 화면에서 고른 값이 사이드바와 개요에 바로 반영된다.
 */
public final class AppSettingsService {

    private final AppSettingsStore store;
    private final List<Consumer<AppSettings>> listeners = new CopyOnWriteArrayList<>();
    private volatile AppSettings current;

    public AppSettingsService(AppSettingsStore store) {
        this.store = store;
        this.current = store.load();
    }

    public AppSettings current() {
        return current;
    }

    /** 값을 바꿔서 저장하고 알린다. 저장하지 못하면 예외를 던지고, 그때는 값도 바뀌지 않는다. */
    public synchronized void update(UnaryOperator<AppSettings> change) {
        AppSettings next = change.apply(current);
        if (next.equals(current)) {
            return;
        }
        store.save(next);
        current = next;
        listeners.forEach(l -> l.accept(next));
    }

    /** 값이 바뀔 때마다 불린다. 바꾼 스레드에서 불리므로, 화면을 건드리는 쪽은 EDT에서 바꿔야 한다. */
    public void onChange(Consumer<AppSettings> listener) {
        listeners.add(listener);
    }
}
