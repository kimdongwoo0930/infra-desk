package com.infradesk.storage;

import com.infradesk.core.AppSettings;

/** 설정 "일반"의 값(시작 화면, 서버 묶기)을 저장한다. */
public interface AppSettingsStore {

    /** 저장된 값. 저장된 것이 없거나 읽을 수 없으면 {@link AppSettings#DEFAULT}. */
    AppSettings load();

    void save(AppSettings settings);
}
