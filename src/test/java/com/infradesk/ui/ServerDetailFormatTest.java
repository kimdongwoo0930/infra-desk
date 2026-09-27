package com.infradesk.ui;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerDetailFormatTest {

    @Test
    void uptime() {
        assertEquals("14일 6시간", ServerDetailPanel.formatUptime(Duration.ofDays(14).plusHours(6).plusMinutes(30)));
        assertEquals("3시간 12분", ServerDetailPanel.formatUptime(Duration.ofHours(3).plusMinutes(12)));
        assertEquals("1분", ServerDetailPanel.formatUptime(Duration.ofSeconds(20)));
    }

    @Test
    void ports() {
        assertEquals("없음", ServerDetailPanel.formatPorts(List.of()));
        assertEquals("22, 80, 443", ServerDetailPanel.formatPorts(List.of(22, 80, 443)));
        assertEquals("22, 80, 443, 3000 외 2개", ServerDetailPanel.formatPorts(List.of(22, 80, 443, 3000, 5432, 8080)));
    }
}
