package com.infradesk.storage;

import com.infradesk.core.AppSettings;

/** 데모 모드와 테스트용 비영속 저장소. 데모 모드는 실제 설정 파일을 읽거나 쓰지 않는다. */
public class InMemoryAppSettingsStore implements AppSettingsStore {

    private volatile AppSettings settings;

    public InMemoryAppSettingsStore() {
        this(AppSettings.DEFAULT);
    }

    public InMemoryAppSettingsStore(AppSettings initial) {
        this.settings = initial;
    }

    @Override
    public AppSettings load() {
        return settings;
    }

    @Override
    public void save(AppSettings settings) {
        this.settings = settings;
    }
}
