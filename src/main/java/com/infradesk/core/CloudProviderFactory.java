package com.infradesk.core;

import java.util.List;
import java.util.Map;

/** Creates the {@link CloudProvider} for an account. Implementations live in provider packages. */
public interface CloudProviderFactory {

    /**
     * @param account the account to connect to
     * @param secrets secret values for the account (e.g. {@code privateKey}); never logged
     */
    CloudProvider create(Account account, Map<String, String> secrets);

    /** Region identifiers offered in the add-account dialog, most common first. */
    default List<String> regions() {
        return List.of();
    }
}
