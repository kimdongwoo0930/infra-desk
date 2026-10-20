package com.infradesk.service;

import com.infradesk.core.Account;
import com.infradesk.core.CloudProvider;
import com.infradesk.core.CloudProviderException;
import com.infradesk.core.CloudProviderFactory;
import com.infradesk.core.ProviderType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** {@link Account#provider()}로 {@link CloudProviderFactory}를 고른다. */
public class ProviderRegistry implements CloudProviderFactory {

    private final Map<ProviderType, CloudProviderFactory> factories = new EnumMap<>(ProviderType.class);

    public ProviderRegistry register(ProviderType type, CloudProviderFactory factory) {
        factories.put(type, factory);
        return this;
    }

    @Override
    public CloudProvider create(Account account, Map<String, String> secrets) {
        return factoryFor(account.provider()).create(account, secrets);
    }

    public List<String> regions(ProviderType type) {
        CloudProviderFactory f = factories.get(type);
        return f == null ? List.of() : f.regions();
    }

    private CloudProviderFactory factoryFor(ProviderType type) {
        CloudProviderFactory f = factories.get(type);
        if (f == null) {
            throw new CloudProviderException(type.displayName() + "은(는) 아직 지원하지 않아요");
        }
        return f;
    }
}
