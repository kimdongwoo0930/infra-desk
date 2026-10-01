package com.infradesk.app;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code --self-test}: 앱이 쓰는 방식 그대로 주요 라이브러리를 모두 불러보고 결과를 출력한다.
 * 창을 열거나 설정, 키체인, 네트워크를 건드리지 않는다. 패키징된 앱을 점검하는 데 쓴다
 * (jlink 런타임에 라이브러리가 필요로 하는 모듈이 모두 들어 있어야 한다).
 */
final class SelfTest {

    private SelfTest() {
    }

    static int run() {
        List<String> failures = new ArrayList<>();
        check(failures, "FlatLaf", () -> Class.forName("com.formdev.flatlaf.themes.FlatMacDarkLaf"));
        check(failures, "SVG icons", () -> new com.formdev.flatlaf.extras.FlatSVGIcon("com/infradesk/ui/icons/app.svg").getIconWidth());
        check(failures, "App icon", () -> AppIcon.images().size());
        check(failures, "Build info", () -> {
            BuildInfo b = BuildInfo.current();
            if (b.version().equals("?")) {
                throw new IllegalStateException("build-info.properties missing");
            }
            return b.display();
        });
        check(failures, "License notices", () -> {
            for (String name : new String[] {"LICENSE.txt", "THIRD-PARTY-NOTICES.txt"}) {
                if (BuildInfo.class.getResource(name) == null) {
                    throw new IllegalStateException(name + " missing");
                }
            }
            return "ok";
        });
        check(failures, "Jackson JSON", () -> new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("a", 1)));
        check(failures, "OCI SDK (offline)", () -> {
            var account = new com.infradesk.core.Account("t", "t", com.infradesk.core.ProviderType.ORACLE, "ap-chuncheon-1",
                    java.util.Map.of("tenancyOcid", "ocid1.tenancy.oc1..x", "userOcid", "ocid1.user.oc1..x",
                            "fingerprint", "00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff"));
            var gen = java.security.KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            String pem = "-----BEGIN PRIVATE KEY-----\n"
                    + java.util.Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(gen.generateKeyPair().getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n";
            try (var p = new com.infradesk.provider.oracle.OracleProviderFactory().create(account,
                    java.util.Map.of(com.infradesk.core.AccountSecrets.PRIVATE_KEY, pem))) {
                return p.getClass().getSimpleName();
            }
        });
        check(failures, "MINA SSHD + ed25519", () -> {
            var kp = org.apache.sshd.common.config.keys.KeyUtils.generateKeyPair("ssh-ed25519", 256);
            try (var c = new com.infradesk.ssh.MinaShellConnector(java.nio.file.Files.createTempFile("kh", ""))) {
                return kp.getPublic().getAlgorithm() + ", " + c.getClass().getSimpleName();
            }
        });
        check(failures, "SFTP", () -> Class.forName("org.apache.sshd.sftp.client.SftpClientFactory"));
        check(failures, "JediTerm", () -> Class.forName("com.jediterm.terminal.ui.JediTermWidget"));
        check(failures, "XChart", () -> new org.knowm.xchart.XYChartBuilder().width(10).height(10).build().getClass());
        check(failures, "HTTP client (Discord)", () -> java.net.http.HttpClient.newHttpClient().getClass());
        check(failures, "Keychain library", () -> Class.forName("com.github.javakeyring.Keyring"));
        check(failures, "TLS", () -> javax.net.ssl.SSLContext.getDefault().getProtocol());
        check(failures, "Korean locale", () -> java.time.format.DateTimeFormatter.ofPattern("a h:mm", java.util.Locale.KOREAN)
                .format(java.time.LocalTime.NOON));
        System.out.println(failures.isEmpty() ? "SELF-TEST OK" : "SELF-TEST FAILED: " + failures);
        return failures.isEmpty() ? 0 : 1;
    }

    private static void check(List<String> failures, String name, Callable<?> task) {
        try {
            Object result = task.call();
            System.out.println("  ok    " + name + (result == null ? "" : " (" + result + ")"));
        } catch (Throwable t) {
            failures.add(name);
            System.out.println("  FAIL  " + name + ": " + t);
        }
    }
}
