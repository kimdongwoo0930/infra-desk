package com.infradesk.ui.overview;

import com.infradesk.ssh.Container;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerSummaryCardTest {

    private static Container running(String name, double cpu, String mem) {
        return new Container("a".repeat(64), name, name + ":latest", "running", "Up 14 days", "", cpu, mem, 1.0, "");
    }

    private static Container stopped(String name, String status) {
        return new Container("b".repeat(64), name, name + ":latest", "exited", status, "", -1, "", -1, "");
    }

    @Test
    void headerCountsRunningOverAllAndShowsWhenItWasRead() {
        List<Container> list = List.of(running("a", 1, "1MiB / 2GiB"), running("b", 1, "1MiB / 2GiB"),
                stopped("c", "Exited (0) 3 days ago"));

        String text = ContainerSummaryCard.headerText(list, Instant.parse("2026-10-27T06:31:28Z"));

        assertTrue(text.startsWith("실행 중 2 / 3 · "), text);
        assertTrue(text.endsWith(" 기준"), text);
    }

    @Test
    void headerWithoutATimeOmitsIt() {
        assertEquals("실행 중 0 / 0", ContainerSummaryCard.headerText(List.of(), null));
    }

    @Test
    void runningContainersComeFirstAndKeepTheirOrder() {
        Container r1 = running("r1", 1, "");
        Container s1 = stopped("s1", "Exited (1) 2 hours ago");
        Container r2 = running("r2", 1, "");

        assertEquals(List.of(r1, r2, s1), ContainerSummaryCard.ordered(List.of(r1, s1, r2)));
    }

    @Test
    void stoppedStatusDropsTheExitCode() {
        assertEquals("멈춤 · 3일 전", ContainerSummaryCard.statusText(stopped("w", "Exited (0) 3 days ago")));
        assertEquals("멈춤 · 3일 전", ContainerSummaryCard.statusText(stopped("w", "Exited (137) 3 days ago")));
        assertEquals("Up 14 days", ContainerSummaryCard.statusText(running("w", 1, "")));
    }

    @Test
    void memoryShowsOnlyTheUsedAmountWithASpace() {
        assertEquals("46 MiB", ContainerSummaryCard.memoryText(running("a", 1, "46MiB / 23.4GiB")));
        assertEquals("1.2 GiB", ContainerSummaryCard.memoryText(running("a", 1, "1.2GiB / 23.4GiB")));
        assertEquals("—", ContainerSummaryCard.memoryText(running("a", 1, "")));
        assertEquals("—", ContainerSummaryCard.memoryText(stopped("a", "Exited (0) 1 hour ago")));
    }

    @Test
    void cpuShowsOneDecimalOrADashWhenNotRunning() {
        assertEquals("12.8%", ContainerSummaryCard.cpuText(running("a", 12.8, "")));
        assertEquals("—", ContainerSummaryCard.cpuText(running("a", -1, "")));
        assertEquals("—", ContainerSummaryCard.cpuText(stopped("a", "Exited (0) 1 hour ago")));
    }
}
