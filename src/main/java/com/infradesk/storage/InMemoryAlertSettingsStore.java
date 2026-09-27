package com.infradesk.storage;

import com.infradesk.alert.AlertSettings;

/** Non-persistent store for demo mode and tests. */
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
