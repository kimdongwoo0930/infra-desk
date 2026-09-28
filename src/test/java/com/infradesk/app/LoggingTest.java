package com.infradesk.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoggingTest {

    @Test
    void hidesPrivateKeys() {
        String s = Logging.redact("key=-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\nBBBB\n-----END OPENSSH PRIVATE KEY----- done");
        assertEquals("key=[private key hidden] done", s);
        assertEquals("oops [private key hidden]", Logging.redact("oops -----BEGIN RSA PRIVATE KEY-----\nAAAA truncated"));
    }

    @Test
    void hidesWebhooks() {
        String s = Logging.redact("posting to https://discord.com/api/webhooks/123/abc-DEF failed");
        assertEquals("posting to [discord webhook hidden] failed", s);
    }

    @Test
    void masksPublicIpsKeepsPrivateOnes() {
        assertEquals("ubuntu@x.x.x.24:22 via 10.0.0.12", Logging.redact("ubuntu@203.0.113.24:22 via 10.0.0.12"));
        assertEquals("Ubuntu 22.04.4 LTS", Logging.redact("Ubuntu 22.04.4 LTS"));
    }

    @Test
    void shortensOcids() {
        String s = Logging.redact("instance ocid1.instance.oc1.ap-chuncheon-1.anwxeljrabcdefghijklmnop123456 stopped");
        assertTrue(s.contains("ocid1.instance.oc1.ap-chuncheon-1.…123456"), s);
        assertFalse(s.contains("abcdefghijklmnop"));
    }
}
