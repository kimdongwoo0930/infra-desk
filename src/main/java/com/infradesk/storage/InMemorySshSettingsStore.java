package com.infradesk.storage;

import com.infradesk.ssh.SshSettings;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 데모 모드와 테스트용 비영속 저장소. */
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
