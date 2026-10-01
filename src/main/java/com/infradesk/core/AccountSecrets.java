package com.infradesk.core;

/** 계정별로 저장하는, 이름이 정해진 비밀값. */
public final class AccountSecrets {

    /** PEM 인코딩된 API 서명 키. */
    public static final String PRIVATE_KEY = "privateKey";

    private AccountSecrets() {
    }
}
