package com.infradesk.ssh;

import java.util.Objects;

/**
 * Where and how to connect. Holds the private key in memory only for the duration of a connect.
 *
 * @param privateKeyPem OpenSSH or PEM private key; never logged
 * @param passphrase    key passphrase, or null
 */
public record SshTarget(String host, int port, String username, String privateKeyPem, String passphrase) {

    public SshTarget {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(privateKeyPem, "privateKeyPem");
    }

    /** "user@host:port" for status bars. */
    public String address() {
        return username + "@" + host + ":" + port;
    }

    @Override
    public String toString() {
        return "SshTarget[" + address() + "]";
    }
}
