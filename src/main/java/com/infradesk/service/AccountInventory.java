package com.infradesk.service;

import com.infradesk.core.Account;
import com.infradesk.core.Server;

import java.util.List;

/**
 * Result of loading one account's servers.
 *
 * @param error user-facing message when loading failed, otherwise null
 */
public record AccountInventory(Account account, List<Server> servers, String error) {

    public AccountInventory {
        servers = List.copyOf(servers);
    }

    public boolean failed() {
        return error != null;
    }
}
