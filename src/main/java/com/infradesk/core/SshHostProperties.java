package com.infradesk.core;

import java.util.Map;

/** Non-secret settings of an SSH account, kept in {@link Account#properties()}. */
public final class SshHostProperties {

    public static final String HOST = "sshHost";
    public static final String PORT = "sshPort";
    /** Region value for SSH accounts; shown as "SSH". */
    public static final String REGION = "ssh";

    private SshHostProperties() {
    }

    /** Id of the one server of an SSH account; stable, so SSH settings and keys stay attached to it. */
    public static String serverId(String accountId) {
        return "ssh-" + accountId;
    }

    public static Map<String, String> of(String host, int port) {
        return Map.of(HOST, host.strip(), PORT, Integer.toString(port));
    }

    public static String host(Account account) {
        String host = account.property(HOST);
        return host == null ? "" : host;
    }

    public static int port(Account account) {
        try {
            return Integer.parseInt(account.properties().getOrDefault(PORT, "22"));
        } catch (NumberFormatException e) {
            return 22;
        }
    }

    /** A host name or address someone could type: letters, digits, dots, dashes, colons (IPv6). */
    public static boolean isValidHost(String host) {
        return host != null && host.strip().matches("[A-Za-z0-9._:-]{1,253}") && !host.strip().startsWith("-");
    }
}
