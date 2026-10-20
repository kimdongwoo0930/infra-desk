package com.infradesk.service;

import com.infradesk.core.Account;
import com.infradesk.core.AccountSecrets;
import com.infradesk.core.CloudProvider;
import com.infradesk.core.Server;
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
 * 계정, 그 자격 증명, provider. 모든 메서드가 I/O에서 블로킹될 수 있으므로 EDT 밖에서 호출한다.
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

    /** 모든 계정의 서버를 병렬로 불러온다. 한 계정이 실패해도 다른 계정은 영향받지 않는다. */
    public List<AccountInventory> loadAll() {
        return load(accounts());
    }

    /** 계정 하나의 서버를 불러온다. 실패는 던지지 않고 결과에 담아 알린다. */
    public AccountInventory load(Account account) {
        try {
            return new AccountInventory(account, provider(account).listServers(), null);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to load servers for account " + account.id() + ": " + e.getMessage());
            return new AccountInventory(account, List.of(), e.getMessage());
        }
    }

    /** 주어진 계정만 병렬로 불러온다. */
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

    /** 서버를 시작, 정지, 재부팅한다. provider가 요청을 받아들이면 반환한다. */
    public void control(Account account, String serverId, ServerAction action) {
        CloudProvider p = provider(account);
        switch (action) {
            case START -> p.start(serverId);
            case STOP -> p.stop(serverId);
            case REBOOT -> p.reboot(serverId);
        }
    }

    /** 서버 하나의 최근 1시간 메트릭. */
    public com.infradesk.core.Metrics metrics(Account account, String serverId) {
        return provider(account).getMetrics(serverId);
    }

    /** 서버 하나의 주어진 기간 메트릭(해상도는 provider가 정한다). */
    public com.infradesk.core.Metrics metrics(Account account, String serverId, java.time.Duration range) {
        return provider(account).getMetrics(serverId, range);
    }

    /**
     * 불러온 모든 계정에 걸쳐 서버별 최신 CPU를 병렬로 가져온다. 실패한 계정은 건너뛴다
     * (사이드바에는 그 서버의 퍼센트가 표시되지 않을 뿐이다).
     */
    public Map<String, Double> currentCpu(List<AccountInventory> inventory) {
        Map<String, Double> result = new ConcurrentHashMap<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (AccountInventory inv : inventory) {
                if (inv.failed() || inv.servers().isEmpty()) {
                    continue;
                }
                pool.submit(() -> {
                    try {
                        result.putAll(provider(inv.account()).currentCpu(inv.servers()));
                    } catch (RuntimeException e) {
                        LOG.log(Level.FINE, "CPU lookup failed for account " + inv.account().id() + ": " + e.getMessage());
                    }
                });
            }
        }
        return result;
    }

    /** 저장된 계정의 캐시된 provider. */
    public CloudProvider provider(Account account) {
        return providers.computeIfAbsent(account.id(), id -> registry.create(account, secretsOf(id)));
    }

    /**
     * 저장하지 않은 설정으로 연결해서 서버를 조회한다. 서버 수를 돌려준다. 주어지지 않은 비밀값은
     * 계정에 저장된 값으로 대체한다(기존 계정을 편집할 때).
     */
    public int testConnection(Account account, Map<String, String> secrets) {
        return preview(account, secrets).size();
    }

    /** 저장하지 않은 계정이 가질 서버를 조회한다(예: 직접 연결 호스트가 응답하는지). */
    public List<Server> preview(Account account, Map<String, String> secrets) {
        Map<String, String> merged = new HashMap<>(secretsOf(account.id()));
        merged.putAll(secrets);
        try (CloudProvider p = registry.create(account, merged)) {
            return p.listServers();
        }
    }

    /**
     * 기존 계정의 설정을 id와 위치를 유지한 채 바꾼다. 주어진 비밀값만 교체하고 나머지는
     * 저장된 그대로 둔다.
     */
    public void updateAccount(Account account, Map<String, String> newSecrets) {
        newSecrets.forEach((name, value) -> secretStore.put(SecretStore.accountKey(account.id(), name), value));
        List<Account> updated = new ArrayList<>(accounts());
        boolean replaced = false;
        for (int i = 0; i < updated.size(); i++) {
            if (updated.get(i).id().equals(account.id())) {
                updated.set(i, account);
                replaced = true;
            }
        }
        if (!replaced) {
            updated.add(account);
        }
        accountStore.save(updated);
        closeProvider(account.id());
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
