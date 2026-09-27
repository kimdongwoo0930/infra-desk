package com.infradesk.ssh;

/** Asks the user whether to trust a host key seen for the first time. May block. */
@FunctionalInterface
public interface HostKeyPrompt {

    /**
     * @param fingerprint e.g. "SHA256:abc…"
     * @return true to trust and remember the key
     */
    boolean trustNewHost(String host, int port, String keyType, String fingerprint);
}
