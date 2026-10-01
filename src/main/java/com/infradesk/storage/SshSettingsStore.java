package com.infradesk.storage;

import com.infradesk.ssh.SshSettings;

import java.util.Optional;

/** 서버별 SSH 설정(키는 제외). */
public interface SshSettingsStore {

    Optional<SshSettings> get(String serverId);

    void put(SshSettings settings);

    void delete(String serverId);
}
