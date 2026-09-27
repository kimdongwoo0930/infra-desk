package com.infradesk.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerStatusTest {

    @Test
    void transitionalStates() {
        assertTrue(ServerStatus.STARTING.isTransitional());
        assertTrue(ServerStatus.STOPPING.isTransitional());
        assertTrue(ServerStatus.REBOOTING.isTransitional());
        assertFalse(ServerStatus.RUNNING.isTransitional());
        assertFalse(ServerStatus.STOPPED.isTransitional());
        assertFalse(ServerStatus.UNKNOWN.isTransitional());
    }

    @Test
    void allowedActions() {
        assertTrue(ServerStatus.STOPPED.canStart());
        assertFalse(ServerStatus.RUNNING.canStart());
        assertTrue(ServerStatus.RUNNING.canStop());
        assertTrue(ServerStatus.RUNNING.canReboot());
        assertFalse(ServerStatus.STOPPING.canStop());
    }
}
