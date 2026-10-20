package com.infradesk.ssh;

/** 처음 보는 호스트 키를 신뢰할지 사용자에게 묻는다. 블로킹될 수 있다. */
@FunctionalInterface
public interface HostKeyPrompt {

    /**
     * @param fingerprint 예: "SHA256:abc…"
     * @return 키를 신뢰하고 기억하려면 true
     */
    boolean trustNewHost(String host, int port, String keyType, String fingerprint);
}
