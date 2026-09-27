package com.infradesk.storage;

import com.github.javakeyring.BackendNotSupportedException;
import com.github.javakeyring.Keyring;
import com.github.javakeyring.PasswordAccessException;

import java.util.Optional;

/** OS keychain: macOS Keychain, Windows Credential Manager, Linux Secret Service. */
public class KeychainSecretStore implements SecretStore {

    private static final String SERVICE = "InfraDesk";

    private final Keyring keyring;

    public KeychainSecretStore() {
        try {
            this.keyring = Keyring.create();
        } catch (BackendNotSupportedException e) {
            throw new StorageException("이 OS의 키체인을 사용할 수 없어요", e);
        }
    }

    @Override
    public Optional<String> get(String key) {
        try {
            return Optional.ofNullable(keyring.getPassword(SERVICE, key));
        } catch (PasswordAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, String value) {
        try {
            keyring.setPassword(SERVICE, key, value);
        } catch (PasswordAccessException e) {
            throw new StorageException("키체인에 저장하지 못했어요", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            keyring.deletePassword(SERVICE, key);
        } catch (PasswordAccessException e) {
            // Already absent.
        }
    }
}
