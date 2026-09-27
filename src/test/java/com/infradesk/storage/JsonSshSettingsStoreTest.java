package com.infradesk.storage;

import com.infradesk.ssh.SshSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonSshSettingsStoreTest {

    @TempDir
    Path dir;

    @Test
    void roundTripAndDelete() throws Exception {
        JsonSshSettingsStore store = new JsonSshSettingsStore(dir);
        store.put(new SshSettings("srv-1", "ubuntu", 22));
        store.put(new SshSettings("srv-2", "opc", 2222));

        JsonSshSettingsStore reopened = new JsonSshSettingsStore(dir);
        assertEquals(new SshSettings("srv-2", "opc", 2222), reopened.get("srv-2").orElseThrow());

        reopened.delete("srv-1");
        assertTrue(new JsonSshSettingsStore(dir).get("srv-1").isEmpty());
        assertFalse(Files.readString(dir.resolve("ssh-settings.json")).contains("PRIVATE KEY"));
    }
}
