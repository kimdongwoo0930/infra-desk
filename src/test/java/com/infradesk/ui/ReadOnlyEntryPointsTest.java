package com.infradesk.ui;

import com.infradesk.alert.AlertSettings;
import com.infradesk.core.Account;
import com.infradesk.core.ProviderType;
import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.infradesk.service.AccountInventory;
import com.infradesk.ui.metrics.MetricsPanel;
import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 읽기 모드는 순수하게 보기만 한다: 계정·서버 추가와 설정, 설정을 바꾸는 우클릭 메뉴와 [SSH 설정], 공인 IP를 보이게 하거나
 * 복사하는 버튼이 모두 없다. 관리 모드로 돌아오면 다시 있다.
 */
class ReadOnlyEntryPointsTest {

    private static final Account ACCOUNT = new Account("acc-1", "계정 A", ProviderType.ORACLE, "ap-chuncheon-1", Map.of());
    private static final Server RUNNING = new Server("s1", "acc-1", "discord-bot", ServerStatus.RUNNING,
            "ap-chuncheon-1", "VM.Test", 2, 12, "203.0.113.24", "10.0.0.12", null);

    private static Sidebar sidebar() {
        Sidebar s = new Sidebar();
        s.setInventory(List.of(new AccountInventory(ACCOUNT, List.of(RUNNING), null)));
        return s;
    }

    private static <T> List<T> all(Container root, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component c : root.getComponents()) {
            if (type.isInstance(c)) {
                found.add(type.cast(c));
            }
            if (c instanceof Container inner) {
                found.addAll(all(inner, type));
            }
        }
        return found;
    }

    // ── 사이드바 ────────────────────────────────────────────────

    @Test
    void inManageModeTheSidebarOffersAddAndSettings() {
        Sidebar s = sidebar();

        assertTrue(SwingTree.buttons(s).contains("계정·서버 추가"));
        assertTrue(SwingTree.buttons(s).contains("설정"));
    }

    @Test
    void inReadOnlyModeTheSidebarHasNoAddOrSettingsButton() {
        Sidebar s = sidebar();

        s.setReadOnly(true);

        assertFalse(SwingTree.buttons(s).contains("계정·서버 추가"));
        assertFalse(SwingTree.buttons(s).contains("설정"));
        assertTrue(SwingTree.labels(s).stream().anyMatch(t -> t != null && t.contains("discord-bot")), "서버 목록은 그대로 보인다");
    }

    @Test
    void inReadOnlyModeServerRowsAndAccountHeadersHaveNoEditMenu() {
        Sidebar s = sidebar();
        assertNotNull(all(s, ServerListItem.class).getFirst().getComponentPopupMenu(), "관리 모드에는 SSH 설정·계정 설정 메뉴가 있다");

        s.setReadOnly(true);

        assertNull(all(s, ServerListItem.class).getFirst().getComponentPopupMenu());
        boolean anyHeaderMenu = all(s, JComponent.class).stream()
                .anyMatch(c -> c.getComponentPopupMenu() != null && !(c instanceof ServerListItem));
        assertFalse(anyHeaderMenu, "계정 헤더의 계정 설정·삭제 메뉴도 없어야 한다");
    }

    @Test
    void backInManageModeTheSidebarOffersThemAgain() {
        Sidebar s = sidebar();
        s.setReadOnly(true);

        s.setReadOnly(false);

        assertTrue(SwingTree.buttons(s).contains("계정·서버 추가"));
        assertNotNull(all(s, ServerListItem.class).getFirst().getComponentPopupMenu());
    }

    // ── 서버 정보 카드 ──────────────────────────────────────────

    private static ServerDetailPanel detail() {
        ServerDetailPanel d = new ServerDetailPanel();
        d.show(RUNNING, ACCOUNT);
        return d;
    }

    @Test
    void inManageModeTheInfoCardHasSshSettingsAndTheIpButtons() {
        List<String> buttons = SwingTree.buttons(detail());

        assertTrue(buttons.contains("SSH 설정"));
        assertTrue(buttons.contains("공인 IP 보기"));
        assertTrue(buttons.contains("공인 IP 복사"));
    }

    @Test
    void inReadOnlyModeThereIsNoWayToRevealOrCopyThePublicIpOrEditSsh() {
        ServerDetailPanel d = detail();

        d.setReadOnly(true);

        List<String> buttons = SwingTree.buttons(d);
        assertFalse(buttons.contains("SSH 설정"));
        assertFalse(buttons.contains("공인 IP 보기"));
        assertFalse(buttons.contains("공인 IP 숨기기"));
        assertFalse(buttons.contains("공인 IP 복사"), "복사하면 가려진 진짜 주소가 클립보드로 나간다");
    }

    @Test
    void theIpButtonsStayHiddenWhenAServerIsShownAfterTheModeWasSet() {
        // 앱을 켜면 읽기 모드가 먼저 정해지고, 서버는 나중에 열린다. 서버를 열 때 IP 칸을 다시 그려도 버튼이 되살아나면 안 된다.
        ServerDetailPanel d = new ServerDetailPanel();
        d.setReadOnly(true);

        d.show(RUNNING, ACCOUNT);

        assertFalse(SwingTree.buttons(d).contains("공인 IP 보기"));
        assertFalse(SwingTree.buttons(d).contains("공인 IP 복사"));
    }

    @Test
    void theIpButtonsComeBackInManageModeAfterAServerIsShown() {
        ServerDetailPanel d = new ServerDetailPanel();
        d.setReadOnly(true);
        d.show(RUNNING, ACCOUNT);

        d.setReadOnly(false);

        assertTrue(SwingTree.buttons(d).contains("공인 IP 보기"));
        assertTrue(SwingTree.buttons(d).contains("공인 IP 복사"));
    }

    // ── 모니터링 탭 ─────────────────────────────────────────────

    @Test
    void inReadOnlyModeTheMonitoringTabHasNoLinkIntoSettings() {
        MetricsPanel panel = new MetricsPanel();
        panel.setAlert(new AlertSettings(true, true, true, true, 80, 3));
        assertTrue(SwingTree.buttons(panel).contains("설정에서 바꾸기"));

        panel.setReadOnly(true);

        assertFalse(SwingTree.buttons(panel).contains("설정에서 바꾸기"));
        assertFalse(SwingTree.buttons(panel).contains("설정에서 켜기"));
    }
}
