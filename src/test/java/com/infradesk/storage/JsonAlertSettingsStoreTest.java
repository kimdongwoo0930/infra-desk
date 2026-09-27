package com.infradesk.storage;

import com.infradesk.alert.AlertSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JsonAlertSettingsStoreTest {

    @TempDir
    Path dir;

    @Test
    void defaultsThenRoundTripWithoutSecrets() throws Exception {
        JsonAlertSettingsStore store = new JsonAlertSettingsStore(dir);
        assertEquals(AlertSettings.DEFAULT, store.load());
        AlertSettings s = new AlertSettings(true, false, true, true, 85, 10);
        store.save(s);
        assertEquals(s, new JsonAlertSettingsStore(dir).load());
        assertFalse(Files.readString(dir.resolve("alerts.json")).contains("webhook"));
    }
}
