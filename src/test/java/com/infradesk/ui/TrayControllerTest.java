package com.infradesk.ui;

import com.infradesk.core.Account;
import com.infradesk.core.ProviderType;
import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.infradesk.service.AccountInventory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrayControllerTest {

    private static Server server(String name, ServerStatus status) {
        return new Server("id-" + name, "a", name, status, "r", "s", 1, 1, null, null, null);
    }

    private static final Account A = new Account("a", "계정 A", ProviderType.ORACLE, "ap-chuncheon-1", Map.of());
    private static final Account B = new Account("b", "B", ProviderType.ORACLE, "ap-seoul-1", Map.of());

    @Test
    void summaryCountsAndAttention() {
        var inv = List.of(
                new AccountInventory(A, List.of(server("bot", ServerStatus.RUNNING), server("box", ServerStatus.STOPPED)), null),
                new AccountInventory(B, List.of(server("web", ServerStatus.STOPPING)), null));
        TrayController.Summary s = TrayController.Summary.of(inv);
        assertEquals("실행 중 1 / 3대 · 변경 중 1", s.headline());
        assertTrue(s.needsAttention());
    }

    @Test
    void failedAccountNeedsAttention() {
        var s = TrayController.Summary.of(List.of(new AccountInventory(A, List.of(), "인증 실패")));
        assertTrue(s.needsAttention());
        assertTrue(s.headline().contains("계정 오류 1"));
    }

    @Test
    void allStableNeedsNoAttention() {
        var s = TrayController.Summary.of(List.of(new AccountInventory(A, List.of(server("bot", ServerStatus.RUNNING)), null)));
        assertFalse(s.needsAttention());
    }

    @Test
    void labelsCarryStatusInText() {
        assertEquals("🟢  bot  —  CPU 23%", TrayController.label(server("bot", ServerStatus.RUNNING), 23.4));
        assertEquals("🟢  bot  —  실행 중", TrayController.label(server("bot", ServerStatus.RUNNING), null));
        assertEquals("⚪  box  —  정지됨", TrayController.label(server("box", ServerStatus.STOPPED), null));
        assertEquals("🟡  web  —  정지 중", TrayController.label(server("web", ServerStatus.STOPPING), 5.0));
    }
}
