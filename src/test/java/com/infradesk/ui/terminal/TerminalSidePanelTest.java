package com.infradesk.ui.terminal;

import com.infradesk.ssh.SavedCommand;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerminalSidePanelTest {

    private static final List<SavedCommand> ALL = List.of(
            new SavedCommand("1", "봇 재시작", "docker restart bot-app"),
            new SavedCommand("2", "디스크 확인", "df -h && du -sh /var/lib/docker"),
            new SavedCommand("3", "패키지 업데이트", "sudo apt update && sudo apt upgrade"));

    @Test
    void emptyQueryShowsEverything() {
        assertEquals(ALL, TerminalSidePanel.filter(ALL, ""));
        assertEquals(ALL, TerminalSidePanel.filter(ALL, "   "));
        assertEquals(ALL, TerminalSidePanel.filter(ALL, null));
    }

    @Test
    void matchesTheNameOrTheCommandIgnoringCase() {
        assertEquals(List.of(ALL.get(0)), TerminalSidePanel.filter(ALL, "재시작"));
        assertEquals(List.of(ALL.get(1)), TerminalSidePanel.filter(ALL, "DU -SH"));
        assertEquals(List.of(ALL.get(0), ALL.get(1)), TerminalSidePanel.filter(ALL, "docker"));
    }

    @Test
    void noMatchGivesAnEmptyList() {
        assertEquals(List.of(), TerminalSidePanel.filter(ALL, "없는명령"));
    }
}
