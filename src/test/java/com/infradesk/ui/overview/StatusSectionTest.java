package com.infradesk.ui.overview;

import com.infradesk.core.Metrics;
import com.infradesk.core.Metrics.Sample;
import com.infradesk.ssh.HostFacts;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusSectionTest {

    private static final Instant T0 = Instant.parse("2026-10-27T06:00:00Z");

    private static Sample at(int minutes, double value) {
        return new Sample(T0.plus(Duration.ofMinutes(minutes)), value);
    }

    @Test
    void sparklineKeepsOnlyTheLastHourEvenWhenALongerRangeIsLoaded() {
        List<Sample> series = List.of(at(0, 1), at(30, 2), at(61, 3), at(90, 4), at(120, 5));

        // 가장 최근 점(120분) 기준 60분 전 = 60분. 그 뒤의 값만.
        assertEquals(List.of(3.0, 4.0, 5.0), StatusSection.lastHour(series));
    }

    @Test
    void sparklineOfEmptyDataIsEmpty() {
        assertEquals(List.of(), StatusSection.lastHour(List.of()));
    }

    @Test
    void sumByTimeAddsReceiveAndSendAtTheSameTime() {
        List<Sample> sum = StatusSection.sumByTime(List.of(at(0, 10), at(1, 20)), List.of(at(0, 1), at(1, 2), at(2, 5)));

        assertEquals(List.of(11.0, 22.0, 5.0), sum.stream().map(Sample::value).toList());
    }

    @Test
    void cpuSubTextNamesTheCoresInTheRightUnit() {
        assertEquals("2 OCPU · 최근 1시간", StatusSection.cpuSub(2, true));
        assertEquals("4코어 · 최근 1시간", StatusSection.cpuSub(4, false));
        assertEquals("1.5 OCPU · 최근 1시간", StatusSection.cpuSub(1.5, true));
        assertEquals("최근 1시간", StatusSection.cpuSub(0, true), "코어 수를 모르면");
    }

    @Test
    void memorySubTextShowsUsedOverTotal() {
        assertEquals("5.4 / 12 GB", StatusSection.memorySub(45, 12));
        assertEquals("12 / 24 GB", StatusSection.memorySub(50, 24), "10 이상은 정수");
        assertEquals("최근 1시간", StatusSection.memorySub(45, 0), "전체를 모르면");
    }

    @Test
    void networkTextUsesTheSameUnitForTheTotalAndTheParts() {
        String[] text = StatusSection.networkText(28_300, 9_900);

        assertEquals("38.2 KB/s", text[0]);
        assertEquals("수신 28.3 · 송신 9.9", text[1]);
    }

    @Test
    void networkTextHandlesSmallAndLargeRates() {
        assertEquals("0 B/s", StatusSection.networkText(0, 0)[0]);
        assertEquals("수신 0.0 · 송신 0.0", StatusSection.networkText(0, 0)[1]);
        String[] big = StatusSection.networkText(2_500_000, 500_000);
        assertEquals("3.0 MB/s", big[0]);
        assertEquals("수신 2.5 · 송신 0.5", big[1]);
    }

    @Test
    void cardsShowTheLatestValueAndTheirSubtext() {
        StatusSection section = new StatusSection();
        section.onData(new Metrics(List.of(at(0, 20), at(1, 24)), List.of(at(0, 44), at(1, 45)),
                List.of(at(1, 28_300)), List.of(at(1, 9_900))));

        assertEquals("24%", section.cpuValue());
        assertEquals("45%", section.memoryValue());
        assertEquals("38.2 KB/s", section.networkValue());
        assertEquals("수신 28.3 · 송신 9.9", section.networkSubText());
    }

    @Test
    void emptyDataShowsDashesAndTheMessage() {
        StatusSection section = new StatusSection();
        section.onEmpty("서버가 실행 중일 때 표시돼요");

        assertEquals("—", section.cpuValue());
        assertEquals("서버가 실행 중일 때 표시돼요", section.cpuSubText());
        assertEquals("—", section.networkValue());
    }

    @Test
    void diskCardShowsUsedOverTotalFromTheServerInfo() {
        StatusSection section = new StatusSection();
        long gb = 1024L * 1024 * 1024;
        section.setFacts(new HostFacts(Duration.ofDays(9), "Ubuntu", 42 * gb, 99 * gb, List.of(22)));

        assertEquals("42%", section.diskValue());
        assertEquals("42 / 99 GB", section.diskSubText());
    }

    @Test
    void diskCardShowsTheMessageWhenThereIsNoServerInfo() {
        StatusSection section = new StatusSection();
        section.setFactsMessage("SSH 설정 후 표시");

        assertEquals("—", section.diskValue());
        assertEquals("SSH 설정 후 표시", section.diskSubText().strip());
    }

    @Test
    void statusTextIsShownNextToTheTitle() {
        StatusSection section = new StatusSection();
        section.onStatus("OCI 모니터링 · 1분 간격 · 15:31 갱신", false);

        assertTrue(section.sourceText().contains("1분 간격"));
    }
}
