package com.infradesk.storage;

import com.infradesk.alert.AlertSettings;

/** 데모 모드와 테스트용 비영속 저장소. */
public class InMemoryAlertSettingsStore implements AlertSettingsStore {

    private volatile AlertSettings settings;

    public InMemoryAlertSettingsStore(AlertSettings initial) {
        this.settings = initial;
    }

    @Override
    public AlertSettings load() {
        return settings;
    }

    @Override
    public void save(AlertSettings settings) {
        this.settings = settings;
    }
}
