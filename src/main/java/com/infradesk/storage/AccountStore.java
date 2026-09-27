package com.infradesk.storage;

import com.infradesk.core.Account;

import java.util.List;

/** Persists account settings (never secrets). */
public interface AccountStore {

    List<Account> load();

    void save(List<Account> accounts);
}
