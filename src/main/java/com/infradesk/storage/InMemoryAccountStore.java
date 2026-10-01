package com.infradesk.storage;

import com.infradesk.core.Account;

import java.util.List;

/** 데모 모드와 테스트에서 쓰는 비영속 저장소. */
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
