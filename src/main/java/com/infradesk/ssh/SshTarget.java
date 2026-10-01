package com.infradesk.ssh;

import java.util.Objects;

/**
 * 어디에 어떻게 연결할지. 개인키는 연결하는 동안에만 메모리에 둔다.
 *
 * @param privateKeyPem OpenSSH 또는 PEM 개인키. 절대 로그에 남기지 않는다
 * @param passphrase    키 암호. 없으면 null
 */
public record SshTarget(String host, int port, String username, String privateKeyPem, String passphrase) {

    public SshTarget {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(privateKeyPem, "privateKeyPem");
    }

    /** 상태 표시줄용 "user@host:port". */
    public String address() {
        return username + "@" + host + ":" + port;
    }

    @Override
    public String toString() {
        return "SshTarget[" + address() + "]";
    }
}
