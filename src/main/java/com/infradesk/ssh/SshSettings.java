package com.infradesk.ssh;

import java.util.Objects;

/**
 * Non-secret SSH settings for one server. The private key and passphrase live in the secret store.
 *
 * @param serverId provider server id
 * @param username login user, e.g. "ubuntu" or "opc"
 * @param port     SSH port
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
