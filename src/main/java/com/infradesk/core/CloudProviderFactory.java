package com.infradesk.core;

import java.util.List;
import java.util.Map;

/** 계정에 대한 {@link CloudProvider}를 만든다. 구현은 provider 패키지에 있다. */
public interface CloudProviderFactory {

    /**
     * @param account 연결할 계정
     * @param secrets 계정의 비밀값(예: {@code privateKey}). 절대 로그에 남기지 않는다
     */
    CloudProvider create(Account account, Map<String, String> secrets);

    /** 계정 추가 다이얼로그에서 제안하는 리전 식별자. 많이 쓰는 것부터. */
    default List<String> regions() {
        return List.of();
    }
}
