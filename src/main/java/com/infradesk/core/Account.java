package com.infradesk.core;

import java.util.Objects;

/**
 * A cloud account (an OCI tenancy, an AWS account, ...). Holds no secrets; credentials live in
 * the storage layer and are resolved by the provider implementation.
 *
 * @param id          stable local identifier
 * @param displayName name shown in the UI, e.g. "계정 A"
 * @param provider    which cloud this account belongs to
 * @param region      default region identifier, e.g. "ap-chuncheon-1"
 */
public record Account(String id, String displayName, ProviderType provider, String region) {

    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(region, "region");
    }
}
