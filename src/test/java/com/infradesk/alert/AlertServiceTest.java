package com.infradesk.alert;

import com.infradesk.storage.InMemoryAlertSettingsStore;
import com.infradesk.storage.InMemorySecretStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertServiceTest {

    @Test
    void webhookGoesToSecretStoreAndSettingsToStore() {
        InMemorySecretStore secrets = new InMemorySecretStore();
        InMemoryAlertSettingsStore store = new InMemoryAlertSettingsStore(AlertSettings.DEFAULT);
        List<Alert> sent = new CopyOnWriteArrayList<>();
        AlertService svc = new AlertService(store, secrets, sent::add, Clock.systemUTC());
        assertFalse(svc.hasWebhook());

        AlertSettings on = new AlertSettings(true, true, false, true, 80, 2);
        svc.save(on, " https://discord.com/api/webhooks/1/x ");
        assertTrue(svc.hasWebhook());
        assertEquals("https://discord.com/api/webhooks/1/x", secrets.get(AlertService.WEBHOOK_KEY).orElseThrow());
        assertEquals(on, store.load());

        svc.save(on, null);
        assertTrue(svc.hasWebhook(), "null keeps the stored URL");
        svc.save(on, "");
        assertFalse(svc.hasWebhook(), "empty removes it");

        svc.sendTest();
        assertEquals(Alert.Level.INFO, sent.getFirst().level());
    }
}
