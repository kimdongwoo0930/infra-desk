package com.infradesk.storage;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 데모 모드와 테스트에서 쓰는 비영속 비밀값 저장소. */
public class InMemorySecretStore implements SecretStore {

    private final Map<String, String> values = new ConcurrentHashMap<>();

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    @Override
    public void put(String key, String value) {
        values.put(key, value);
    }

    @Override
    public void delete(String key) {
        values.remove(key);
    }
}
