package com.infradesk.storage;

import com.infradesk.ssh.SshSettings;

import java.util.Optional;

/** Per-server SSH settings (never keys). */
public interface SshSettingsStore {

    Optional<SshSettings> get(String serverId);

    void put(SshSettings settings);

    void delete(String serverId);
}
