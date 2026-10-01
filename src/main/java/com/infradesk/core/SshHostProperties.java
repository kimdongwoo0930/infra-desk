package com.infradesk.core;

import java.util.Map;

/** {@link Account#properties()}에 보관하는 SSH 계정의 비밀이 아닌 설정. */
public final class SshHostProperties {

    public static final String HOST = "sshHost";
    public static final String PORT = "sshPort";
    /** SSH 계정의 리전 값. "SSH"로 표시된다. */
    public static final String REGION = "ssh";

    private SshHostProperties() {
    }

    /** SSH 계정의 유일한 서버의 id. 변하지 않으므로 SSH 설정과 키가 계속 그 서버에 붙어 있다. */
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

    /** 누군가 입력할 수 있는 호스트 이름이나 주소: 문자, 숫자, 점, 대시, 콜론(IPv6). */
    public static boolean isValidHost(String host) {
        return host != null && host.strip().matches("[A-Za-z0-9._:-]{1,253}") && !host.strip().startsWith("-");
    }
}
