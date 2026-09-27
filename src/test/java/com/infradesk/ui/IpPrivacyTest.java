package com.infradesk.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IpPrivacyTest {

    @AfterEach
    void hideAgain() {
        IpPrivacy.setRevealed(false);
    }

    @Test
    void hidesPublicAddressesKeepingLastOctet() {
        assertEquals("•••.•••.•••.104", IpPrivacy.display("203.0.113.104"));
        assertEquals("연결됨 · ubuntu@•••.•••.•••.24:22", IpPrivacy.mask("연결됨 · ubuntu@203.0.113.24:22"));
    }

    @Test
    void leavesPrivateAndNonAddressesAlone() {
        for (String s : new String[] {"10.0.0.238", "172.16.4.1", "192.168.55.102", "127.0.0.1", "100.64.0.9",
                "Ubuntu 22.04.4 LTS", "v1.2.3.4000", "999.1.1.1"}) {
            assertEquals(s, IpPrivacy.mask(s));
        }
    }

    @Test
    void revealShowsEverythingAndNotifies() {
        AtomicInteger calls = new AtomicInteger();
        IpPrivacy.onChange(calls::incrementAndGet);
        IpPrivacy.setRevealed(true);
        assertEquals("203.0.113.104", IpPrivacy.display("203.0.113.104"));
        assertEquals(1, calls.get());
    }
}
