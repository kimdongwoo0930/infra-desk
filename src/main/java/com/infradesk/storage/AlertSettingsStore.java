package com.infradesk.storage;

import com.infradesk.alert.AlertSettings;

/** Persists alert toggles and thresholds (not the webhook URL). */
public interface AlertSettingsStore {

    AlertSettings load();

    void save(AlertSettings settings);
}
