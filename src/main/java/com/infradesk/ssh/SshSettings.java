package com.infradesk.ssh;

import java.util.Objects;

/**
 * 서버 하나의 비밀이 아닌 SSH 설정. 개인키와 암호는 비밀값 저장소에 있다.
 *
 * @param serverId provider의 서버 id
 * @param username 로그인 사용자. 예: "ubuntu" 또는 "opc"
 * @param port     SSH 포트
 */
public record SshSettings(String serverId, String username, int port) {

    public static final int DEFAULT_PORT = 22;

    public SshSettings {
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(username, "username");
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
    }
}
