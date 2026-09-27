package com.infradesk.service;

import com.infradesk.core.Account;
import com.infradesk.core.AccountSecrets;
import com.infradesk.core.CloudProvider;
import com.infradesk.core.CloudProviderException;
import com.infradesk.core.ProviderType;
import com.infradesk.storage.AccountStore;
import com.infradesk.storage.SecretStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Accounts, their credentials and providers. All methods may block on I/O; call them off the EDT.
 */
public class InventoryService {

    private static final Logger LOG = Logger.getLogger(InventoryService.class.getName());
    private static final List<String> SECRET_NAMES = List.of(AccountSecrets.PRIVATE_KEY);

    private final AccountStore accountStore;
    private final SecretStore secretStore;
    private final ProviderRegistry registry;
    private final Map<String, CloudProvider> providers = new ConcurrentHashMap<>();

    public InventoryService(AccountStore accountStore, SecretStore secretStore, ProviderRegistry registry) {
        this.accountStore = accountStore;
        this.secretStore = secretStore;
        this.registry = registry;
    }

    public List<Account> accounts() {
        return accountStore.load();
    }

    public List<String> regions(ProviderType type) {
        return registry.regions(type);
    }

    /** Loads every account's servers in parallel. A failing account doesn't fail the others. */
    public List<AccountInventory> loadAll() {
        return load(accounts());
    }

    /** Loads one account's servers; failures are reported in the result, not thrown. */
    public AccountInventory load(Account account) {
        try {
            return new AccountInventory(account, provider(account).listServers(), null);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to load servers for account " + account.id() + ": " + e.getMessage());
            return new AccountInventory(account, List.of(), e.getMessage());
        }
    }

    /** Loads only the given accounts, in parallel. */
    public List<AccountInventory> load(java.util.Collection<Account> accounts) {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<AccountInventory>> futures = new ArrayList<>();
            for (Account a : accounts) {
                futures.add(pool.submit(() -> load(a)));
            }
            List<AccountInventory> result = new ArrayList<>();
            for (Future<AccountInventory> f : futures) {
                result.add(f.get());
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CloudProviderException("새로고침이 중단됐어요", e);
        } catch (ExecutionException e) {
            throw new CloudProviderException("서버 목록을 불러오지 못했어요", e.getCause());
        }
    }

    /** Starts, stops or reboots a server. Returns once the provider accepted the request. */
    public void control(Account account, String serverId, ServerAction action) {
        CloudProvider p = provider(account);
        switch (action) {
            case START -> p.start(serverId);
            case STOP -> p.stop(serverId);
            case REBOOT -> p.reboot(serverId);
        }
    }

    /** Cached provider for a saved account. */
    public CloudProvider provider(Account account) {
        return providers.computeIfAbsent(account.id(), id -> registry.create(account, secretsOf(id)));
    }

    /** Connects with unsaved settings and lists servers. Returns the server count. */
    public int testConnection(Account account, Map<String, String> secrets) {
        try (CloudProvider p = registry.create(account, secrets)) {
            return p.listServers().size();
        }
    }

    public void addAccount(Account account, Map<String, String> secrets) {
        secrets.forEach((name, value) -> secretStore.put(SecretStore.accountKey(account.id(), name), value));
        List<Account> updated = new ArrayList<>(accounts());
        updated.removeIf(a -> a.id().equals(account.id()));
        updated.add(account);
        accountStore.save(updated);
        closeProvider(account.id());
    }

    public void removeAccount(String accountId) {
        List<Account> updated = new ArrayList<>(accounts());
        updated.removeIf(a -> a.id().equals(accountId));
        accountStore.save(updated);
        for (String name : SECRET_NAMES) {
            secretStore.delete(SecretStore.accountKey(accountId, name));
        }
        closeProvider(accountId);
    }

    private Map<String, String> secretsOf(String accountId) {
        Map<String, String> secrets = new HashMap<>();
        for (String name : SECRET_NAMES) {
            secretStore.get(SecretStore.accountKey(accountId, name)).ifPresent(v -> secrets.put(name, v));
        }
        return secrets;
    }

    private void closeProvider(String accountId) {
        CloudProvider p = providers.remove(accountId);
        if (p != null) {
            p.close();
        }
    }
}
