package com.infradesk.storage;

import com.infradesk.core.Account;
import com.infradesk.core.ProviderType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonAccountStoreTest {

    @TempDir
    Path dir;

    @Test
    void missingFileMeansNoAccounts() {
        assertTrue(new JsonAccountStore(dir).load().isEmpty());
    }

    @Test
    void roundTrip() {
        Account a = new Account("id-1", "계정 A", ProviderType.ORACLE, "ap-chuncheon-1",
                Map.of("tenancyOcid", "ocid1.tenancy.oc1..fake"));
        JsonAccountStore store = new JsonAccountStore(dir);
        store.save(List.of(a));
        assertEquals(List.of(a), new JsonAccountStore(dir).load());
    }

    @Test
    void fileIsOwnerOnlyOnPosix() throws Exception {
        new JsonAccountStore(dir).save(List.of());
        Path file = dir.resolve("accounts.json");
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        }
    }
}
