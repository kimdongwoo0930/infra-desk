package com.infradesk.storage;

import com.infradesk.ssh.SshSettings;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Non-persistent store for demo mode and tests. */
public class InMemorySshSettingsStore implements SshSettingsStore {

    private final Map<String, SshSettings> values = new ConcurrentHashMap<>();

    @Override
    public Optional<SshSettings> get(String serverId) {
        return Optional.ofNullable(values.get(serverId));
    }

    @Override
    public void put(SshSettings settings) {
        values.put(settings.serverId(), settings);
    }

    @Override
    public void delete(String serverId) {
        values.remove(serverId);
    }
}
