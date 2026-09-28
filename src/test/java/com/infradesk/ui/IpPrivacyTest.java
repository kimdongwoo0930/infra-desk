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

    @Test
    void directServerAddressesAreHiddenEvenWhenPrivate() {
        boolean before = IpPrivacy.isRevealed();
        IpPrivacy.setRevealed(false);
        try {
            IpPrivacy.protect("192.168.77.5");
            IpPrivacy.protect("home-box");
            IpPrivacy.protect("home-box.tail1234.ts.net");
            assertEquals("•••.•••.•••.5", IpPrivacy.display("192.168.77.5"));
            assertEquals("h•••••", IpPrivacy.display("home-box"));
            assertEquals("직접 연결 (SSH) · h•••••:22", IpPrivacy.mask("직접 연결 (SSH) · home-box:22"));
            assertEquals("ubuntu@h•••••:22", IpPrivacy.mask("ubuntu@home-box.tail1234.ts.net:22"), "longest match first");
            assertEquals("192.168.77.6", IpPrivacy.mask("192.168.77.6"), "other private addresses stay visible");
            IpPrivacy.setRevealed(true);
            assertEquals("home-box:22", IpPrivacy.mask("home-box:22"));
        } finally {
            IpPrivacy.setRevealed(before);
        }
    }
}
