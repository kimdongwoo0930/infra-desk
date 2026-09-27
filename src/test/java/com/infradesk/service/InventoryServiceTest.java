package com.infradesk.service;

import com.infradesk.core.Account;
import com.infradesk.core.AccountSecrets;
import com.infradesk.core.CloudProvider;
import com.infradesk.core.CloudProviderException;
import com.infradesk.core.Metrics;
import com.infradesk.core.ProviderType;
import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.infradesk.storage.InMemoryAccountStore;
import com.infradesk.storage.InMemorySecretStore;
import com.infradesk.storage.SecretStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryServiceTest {

    private static final Account OK = new Account("ok", "OK", ProviderType.ORACLE, "r", Map.of());
    private static final Account BAD = new Account("bad", "BAD", ProviderType.ORACLE, "r", Map.of());

    /** Fails for the "bad" account, returns one server otherwise; records the secrets it got. */
    private static final class FakeProvider implements CloudProvider {
        private final Account account;

        FakeProvider(Account account) {
            this.account = account;
        }

        @Override
        public List<Server> listServers() {
            if (account.id().equals("bad")) {
                throw new CloudProviderException("인증 실패");
            }
            return List.of(new Server("s-" + account.id(), account.id(), "srv", ServerStatus.RUNNING,
                    "r", "shape", 1, 1, null, null, null));
        }

        @Override
        public void start(String id) {
        }

        @Override
        public void stop(String id) {
        }

        @Override
        public void reboot(String id) {
        }

        @Override
        public Metrics getMetrics(String id) {
            return Metrics.EMPTY;
        }
    }

    private InventoryService service(List<Account> accounts, SecretStore secrets, Map<String, Map<String, String>> seen) {
        ProviderRegistry registry = new ProviderRegistry().register(ProviderType.ORACLE, (account, s) -> {
            seen.put(account.id(), s);
            return new FakeProvider(account);
        });
        return new InventoryService(new InMemoryAccountStore(accounts), secrets, registry);
    }

    @Test
    void oneFailingAccountDoesNotBreakOthers() {
        var result = service(List.of(OK, BAD), new InMemorySecretStore(), new java.util.HashMap<>()).loadAll();
        assertEquals(2, result.size());
        assertFalse(result.get(0).failed());
        assertEquals(1, result.get(0).servers().size());
        assertTrue(result.get(1).failed());
        assertTrue(result.get(1).error().contains("인증 실패"));
    }

    @Test
    void addAccountStoresSecretsSeparatelyAndRemoveDeletesThem() {
        InMemorySecretStore secrets = new InMemorySecretStore();
        Map<String, Map<String, String>> seen = new java.util.HashMap<>();
        InventoryService svc = service(List.of(), secrets, seen);

        svc.addAccount(OK, Map.of(AccountSecrets.PRIVATE_KEY, "-----BEGIN PRIVATE KEY-----fake"));
        assertEquals(List.of(OK), svc.accounts());
        assertTrue(secrets.get(SecretStore.accountKey("ok", AccountSecrets.PRIVATE_KEY)).isPresent());

        svc.loadAll();
        assertEquals("-----BEGIN PRIVATE KEY-----fake", seen.get("ok").get(AccountSecrets.PRIVATE_KEY));

        svc.removeAccount("ok");
        assertTrue(svc.accounts().isEmpty());
        assertTrue(secrets.get(SecretStore.accountKey("ok", AccountSecrets.PRIVATE_KEY)).isEmpty());
    }

    @Test
    void unsupportedProviderFailsPerAccount() {
        Account aws = new Account("aws", "AWS", ProviderType.AWS, "r", Map.of());
        var result = service(List.of(aws), new InMemorySecretStore(), new java.util.HashMap<>()).loadAll();
        assertTrue(result.getFirst().failed());
    }
}
