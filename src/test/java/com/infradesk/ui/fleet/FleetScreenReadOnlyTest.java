package com.infradesk.ui.fleet;

import com.infradesk.core.Account;
import com.infradesk.core.OverviewView;
import com.infradesk.core.ProviderType;
import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.infradesk.service.AccountInventory;
import com.infradesk.ui.SwingTree;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 읽기 모드에서 개요가 서버에 접속하거나 서버를 켜는 버튼을 숨기는지. */
class FleetScreenReadOnlyTest {

    private static final Account CLOUD = new Account("a1", "계정 A", ProviderType.ORACLE, "ap-chuncheon-1", Map.of());

    private static Server server(String name, ServerStatus status) {
        return new Server(name, "a1", name, status, "r", "shape", 1, 1, null, null, null);
    }

    private static FleetScreen screen() {
        FleetScreen screen = new FleetScreen();
        screen.setData(List.of(new AccountInventory(CLOUD,
                        List.of(server("web", ServerStatus.RUNNING), server("db", ServerStatus.STOPPED)), null)),
                Map.of(), id -> Optional.empty(), id -> false);
        return screen;
    }

    private static List<String> serverButtons(FleetScreen screen) {
        return SwingTree.buttons(screen).stream().filter(t -> List.of("SSH", "파일", "모니터링", "시작").contains(t)).toList();
    }

    // ── 카드 ────────────────────────────────────────────────────

    @Test
    void inManageModeTheCardsHaveEveryButton() {
        assertEquals(List.of("SSH", "파일", "모니터링", "시작"), serverButtons(screen()));
    }

    @Test
    void inReadOnlyModeTheCardsKeepOnlyMonitoring() {
        FleetScreen screen = screen();

        screen.setReadOnly(true);

        assertEquals(List.of("모니터링"), serverButtons(screen), "SSH·파일·시작은 보이지 않아야 한다(비활성이 아니라 숨김)");
    }

    @Test
    void goingBackToManageModeBringsTheButtonsBack() {
        FleetScreen screen = screen();
        screen.setReadOnly(true);

        screen.setReadOnly(false);

        assertEquals(List.of("SSH", "파일", "모니터링", "시작"), serverButtons(screen));
    }

    // ── 목록 ────────────────────────────────────────────────────

    @Test
    void inReadOnlyModeTheListKeepsOnlyMonitoringToo() {
        FleetScreen screen = screen();
        screen.setView(OverviewView.LIST);
        assertEquals(List.of("SSH", "파일", "모니터링", "시작"), serverButtons(screen));

        screen.setReadOnly(true);

        assertEquals(List.of("모니터링"), serverButtons(screen));
    }

    @Test
    void theModeAndTheViewAreKeptWhenOneOfThemChanges() {
        FleetScreen screen = screen();
        screen.setReadOnly(true);

        screen.setView(OverviewView.LIST);   // 읽기 모드인 채로 목록으로 바꿔도 버튼은 계속 숨어 있어야 한다

        assertEquals(List.of("모니터링"), serverButtons(screen));
        assertFalse(screen.rows().isEmpty());
    }

    @Test
    void theListNeedsLessWidthWhenItHasOnlyOneButton() {
        int name = 110;
        assertTrue(ServerTable.requiredWidth(ServerTable.LEVEL_FULL, name, 112)
                < ServerTable.requiredWidth(ServerTable.LEVEL_FULL, name), "버튼이 하나뿐이면 같은 폭에서 더 오래 모두 보여야 한다");
    }
}
