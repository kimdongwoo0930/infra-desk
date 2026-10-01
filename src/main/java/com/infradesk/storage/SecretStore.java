package com.infradesk.storage;

import java.util.Optional;

/** 비밀 문자열(API 키)을 설정 파일 밖에 저장한다. 값은 절대 로그에 남기지 않는다. */
public interface SecretStore {

    Optional<String> get(String key);

    void put(String key, String value);

    void delete(String key);

    /** 계정의 비밀값을 저장하는 키. */
    static String accountKey(String accountId, String secretName) {
        return "account." + accountId + "." + secretName;
    }
}
