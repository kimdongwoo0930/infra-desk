package com.infradesk.storage;

import com.infradesk.core.Account;

import java.util.List;

/** Non-persistent store used by demo mode and tests. */
public class InMemoryAccountStore implements AccountStore {

    private volatile List<Account> accounts;

    public InMemoryAccountStore(List<Account> initial) {
        this.accounts = List.copyOf(initial);
    }

    @Override
    public List<Account> load() {
        return accounts;
    }

    @Override
    public void save(List<Account> accounts) {
        this.accounts = List.copyOf(accounts);
    }
}
