package com.infradesk.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultSecretStoreTest {

    @TempDir
    Path dir;

    /** Counts reads so tests can check the keychain is touched once. */
    private static final class CountingStore extends InMemorySecretStore {
        final AtomicInteger reads = new AtomicInteger();

        @Override
        public Optional<String> get(String key) {
            reads.incrementAndGet();
            return super.get(key);
        }
    }

    @Test
    void roundTripAcrossInstancesWithOneKeychainItem() throws Exception {
        CountingStore keychain = new CountingStore();
        VaultSecretStore vault = new VaultSecretStore(dir, keychain, null);
        vault.put("account.a.privateKey", "-----BEGIN PRIVATE KEY-----secret");
        vault.put("server.s.sshKey", "ssh-secret");

        VaultSecretStore reopened = new VaultSecretStore(dir, keychain, null);
        assertEquals("ssh-secret", reopened.get("server.s.sshKey").orElseThrow());
        assertEquals("-----BEGIN PRIVATE KEY-----secret", reopened.get("account.a.privateKey").orElseThrow());
        assertTrue(keychain.get(VaultSecretStore.MASTER_KEY_ITEM).isPresent());
        assertFalse(keychain.get("server.s.sshKey").isPresent(), "secrets are not stored in the keychain");
    }

    @Test
    void keychainIsReadOnlyOncePerRun() {
        CountingStore keychain = new CountingStore();
        VaultSecretStore vault = new VaultSecretStore(dir, keychain, null);
        vault.put("a", "1");
        int after = keychain.reads.get();
        for (int i = 0; i < 20; i++) {
            vault.get("a");
            vault.get("missing");
        }
        assertEquals(after, keychain.reads.get());
    }

    @Test
    void fileIsEncryptedAndOwnerOnly() throws Exception {
        VaultSecretStore vault = new VaultSecretStore(dir, new InMemorySecretStore(), null);
        vault.put("server.s.sshKey", "VERY-SECRET-VALUE");
        Path file = dir.resolve("secrets.vault");
        String raw = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("VERY-SECRET-VALUE"));
        assertFalse(raw.contains("server.s.sshKey"), "names are encrypted too");
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        }
    }

    @Test
    void wrongMasterKeyFailsInsteadOfReturningGarbage() {
        new VaultSecretStore(dir, new InMemorySecretStore(), null).put("a", "1");
        VaultSecretStore other = new VaultSecretStore(dir, new InMemorySecretStore(), null);
        assertThrows(StorageException.class, () -> other.get("a"), "no key in this keychain but a vault exists");
        InMemorySecretStore wrong = new InMemorySecretStore();
        wrong.put(VaultSecretStore.MASTER_KEY_ITEM, java.util.Base64.getEncoder().encodeToString(new byte[32]));
        assertThrows(StorageException.class, () -> new VaultSecretStore(dir, wrong, null).get("a"));
    }

    @Test
    void migratesFromLegacyOnceAndRemembersMisses() {
        CountingStore legacy = new CountingStore();
        legacy.put("account.a.privateKey", "old-key");
        InMemorySecretStore keychain = new InMemorySecretStore();
        VaultSecretStore vault = new VaultSecretStore(dir, keychain, legacy);

        assertEquals("old-key", vault.get("account.a.privateKey").orElseThrow());
        vault.get("account.a.privateKey");
        vault.get("server.none.sshKey");
        vault.get("server.none.sshKey");
        assertEquals(2, legacy.reads.get(), "one lookup per name, hit or miss");

        VaultSecretStore reopened = new VaultSecretStore(dir, keychain, new CountingStore());
        assertEquals("old-key", reopened.get("account.a.privateKey").orElseThrow(), "copied into the vault");
    }
}
