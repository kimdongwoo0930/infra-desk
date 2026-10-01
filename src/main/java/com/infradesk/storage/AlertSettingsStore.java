package com.infradesk.storage;

import com.infradesk.alert.AlertSettings;

/** 알림 스위치와 기준값을 저장한다(웹훅 URL은 제외). */
public interface AlertSettingsStore {

    AlertSettings load();

    void save(AlertSettings settings);
}
