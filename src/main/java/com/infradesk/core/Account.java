package com.infradesk.core;

import java.util.Map;
import java.util.Objects;

/**
 * A cloud account (an OCI tenancy, an AWS account, ...). Holds no secrets; credentials live in
 * the storage layer's secret store and are handed to the provider factory separately.
 *
 * @param id          stable local identifier
 * @param displayName name shown in the UI, e.g. "계정 A"
 * @param provider    which cloud this account belongs to
 * @param region      default region identifier, e.g. "ap-chuncheon-1"
 * @param properties  non-secret provider-specific settings (e.g. OCI tenancy/user OCID, fingerprint)
 */
public record Account(String id, String displayName, ProviderType provider, String region,
                      Map<String, String> properties) {

    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(region, "region");
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    public String property(String key) {
        return properties.get(key);
    }
}
