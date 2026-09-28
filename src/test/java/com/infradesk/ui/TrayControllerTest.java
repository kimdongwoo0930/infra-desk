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

    @Test
    void plainLabelDropsEmojiForTheSwingMenu() {
        assertEquals("bot  —  CPU 23%", TrayController.plainLabel(server("bot", ServerStatus.RUNNING), 23.4));
    }

    @Test
    void entriesOfferOnlyActionsTheStateAllows() {
        TrayController.Actions noop = (TrayController.Actions) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{TrayController.Actions.class}, (o, m, a) -> null);
        var inv = List.of(new AccountInventory(A, List.of(server("bot", ServerStatus.RUNNING), server("box", ServerStatus.STOPPED)), null));
        var entries = TrayController.entries(inv, Map.of(), "", false, null, noop);
        var servers = entries.stream().filter(e -> e instanceof TrayController.Entry.ServerMenu)
                .map(e -> (TrayController.Entry.ServerMenu) e).toList();
        assertEquals(2, servers.size());
        assertEquals(List.of("대시보드에서 보기", "SSH 열기", "재부팅…", "정지…"), itemTexts(servers.get(0).items()));
        assertEquals(List.of("대시보드에서 보기", "시작"), itemTexts(servers.get(1).items()));
        assertTrue(entries.getFirst() instanceof TrayController.Entry.Label l && l.text().startsWith("InfraDesk · 실행 중 1 / 2대"));
    }

    @Test
    void directlyConnectedServersOfferNoPowerActions() {
        TrayController.Actions noop = (TrayController.Actions) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{TrayController.Actions.class}, (o, m, a) -> null);
        Account mac = new Account("m", "맥미니", ProviderType.SSH, "ssh", Map.of());
        var entries = TrayController.entries(List.of(new AccountInventory(mac, List.of(server("mac", ServerStatus.RUNNING)), null)),
                Map.of(), "", false, null, noop);
        var sm = entries.stream().filter(e -> e instanceof TrayController.Entry.ServerMenu)
                .map(e -> (TrayController.Entry.ServerMenu) e).findFirst().orElseThrow();
        assertEquals(List.of("대시보드에서 보기", "SSH 열기"), itemTexts(sm.items()));
    }

    private static List<String> itemTexts(List<TrayController.Entry> entries) {
        return entries.stream().filter(e -> e instanceof TrayController.Entry.Item)
                .map(e -> ((TrayController.Entry.Item) e).text()).toList();
    }
}
