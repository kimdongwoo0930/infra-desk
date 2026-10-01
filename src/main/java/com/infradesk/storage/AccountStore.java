package com.infradesk.storage;

import com.infradesk.core.Account;

import java.util.List;

/** 계정 설정을 저장한다(비밀값은 제외). */
public interface AccountStore {

    List<Account> load();

    void save(List<Account> accounts);
}
