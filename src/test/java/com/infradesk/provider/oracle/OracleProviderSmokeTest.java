package com.infradesk.provider.oracle;

import com.infradesk.core.Account;
import com.infradesk.core.AccountSecrets;
import com.infradesk.core.CloudProviderException;
import com.infradesk.core.ProviderType;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the real OCI SDK stack (key parsing, request signing, Jersey HTTP client) without
 * leaving the machine: requests go to a closed local port, so they must fail with a connection
 * error rather than a class-loading or key-format error.
 */
class OracleProviderSmokeTest {

    private static String pkcs8Pem() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(g.generateKeyPair().getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----\n";
    }

    @Test
    void sdkBuildsSignedRequestsAndReportsNetworkFailure() throws Exception {
        Account account = new Account("t", "테스트", ProviderType.ORACLE, "ap-chuncheon-1", Map.of(
                OracleProperties.TENANCY_OCID, "ocid1.tenancy.oc1..aaaafake",
                OracleProperties.USER_OCID, "ocid1.user.oc1..aaaafake",
                OracleProperties.FINGERPRINT, "00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff"));
        try (OracleProvider p = new OracleProvider(account, Map.of(AccountSecrets.PRIVATE_KEY, pkcs8Pem()),
                "http://127.0.0.1:1")) {
            CloudProviderException list = assertThrows(CloudProviderException.class, p::listServers);
            assertTrue(list.getMessage().contains("연결하지 못했어요") || list.getMessage().contains("실패"), list.getMessage());
            CloudProviderException metrics = assertThrows(CloudProviderException.class, () -> p.getMetrics("ocid1.instance.oc1..x"));
            assertTrue(metrics.getMessage().contains("메트릭 조회 실패"), metrics.getMessage());
        }
    }

    @Test
    void unknownRegionIsReported() {
        Account account = new Account("t", "테스트", ProviderType.ORACLE, "mars-north-1", Map.of(
                OracleProperties.TENANCY_OCID, "ocid1.tenancy.oc1..x",
                OracleProperties.USER_OCID, "ocid1.user.oc1..x",
                OracleProperties.FINGERPRINT, "00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff"));
        CloudProviderException e = assertThrows(CloudProviderException.class,
                () -> new OracleProvider(account, Map.of(AccountSecrets.PRIVATE_KEY, "x")));
        assertTrue(e.getMessage().contains("리전"), e.getMessage());
    }
}
