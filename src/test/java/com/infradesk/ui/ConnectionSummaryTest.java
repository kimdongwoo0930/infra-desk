package com.infradesk.ui;

import com.infradesk.ssh.OpenConnection;
import com.infradesk.ssh.Purpose;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConnectionSummaryTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");

    private static OpenConnection open(String server, Purpose purpose, long minutesAgo) {
        return new OpenConnection(server, purpose, NOW.minus(Duration.ofMinutes(minutesAgo)), NOW, 0);
    }

    @Test
    void statusBarCountsConnectionsAndPurposesThatExist() {
        List<OpenConnection> list = List.of(open("a", Purpose.QUERY, 5), open("b", Purpose.QUERY, 5), open("a", Purpose.FILES, 5));

        assertEquals("SSH 연결 3 · 조회 2 · 파일 1", ConnectionSummary.statusBar(list));
    }

    @Test
    void statusBarSaysThereAreNoConnections() {
        assertEquals("SSH 연결 없음", ConnectionSummary.statusBar(List.of()));
    }

    @Test
    void statusBarListsPurposesInTheOrderQueryCommandFilesTransfer() {
        List<OpenConnection> list = List.of(open("a", Purpose.TRANSFER, 1), open("a", Purpose.COMMAND, 1), open("a", Purpose.QUERY, 1));

        assertEquals("SSH 연결 3 · 조회 1 · 명령 1 · 전송 1", ConnectionSummary.statusBar(list));
    }

    @Test
    void elapsedTimeReadsInMinutesHoursAndDays() {
        assertEquals("방금 맺음", ConnectionSummary.elapsed(Duration.ofSeconds(20)));
        assertEquals("12분째", ConnectionSummary.elapsed(Duration.ofMinutes(12)));
        assertEquals("3시간 12분째", ConnectionSummary.elapsed(Duration.ofMinutes(192)));
        assertEquals("2시간째", ConnectionSummary.elapsed(Duration.ofMinutes(120)));
        assertEquals("2일째", ConnectionSummary.elapsed(Duration.ofHours(50)));
    }

    @Test
    void rowStatusUsesTheOldestConnectionAndListsItsPurposes() {
        List<OpenConnection> mine = List.of(open("a", Purpose.QUERY, 192), open("a", Purpose.FILES, 190));

        assertEquals("3시간 12분째 · 조회·파일", ConnectionSummary.rowStatus(mine, NOW));
    }

    @Test
    void rowStatusOfAServerWithoutConnections() {
        assertEquals("연결 없음", ConnectionSummary.rowStatus(List.of(), NOW));
    }
}
