package com.infradesk.storage;

import java.util.Optional;

/** Stores secret strings (API keys) outside the settings file. Values must never be logged. */
public interface SecretStore {

    Optional<String> get(String key);

    void put(String key, String value);

    void delete(String key);

    /** Key under which an account's secret is stored. */
    static String accountKey(String accountId, String secretName) {
        return "account." + accountId + "." + secretName;
    }
}
