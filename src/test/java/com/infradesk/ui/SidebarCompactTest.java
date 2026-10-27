package com.infradesk.ui;

import com.infradesk.core.Account;
import com.infradesk.core.ProviderType;
import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.infradesk.service.AccountInventory;

import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.AbstractButton;
import javax.swing.JLabel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 사이드바의 "이름만 보이는" 좁은 모드. 가짜 값만 쓴다. */
class SidebarCompactTest {

    private static final Account ACCOUNT = new Account("acc-1", "계정 A", ProviderType.ORACLE, "ap-chuncheon-1", Map.of());
    private static final Server RUNNING = new Server("s1", "acc-1", "discord-bot", ServerStatus.RUNNING,
            "ap-chuncheon-1", "VM.Test", 2, 12, null, null, null);
    private static final Server STOPPED = new Server("s2", "acc-1", "batch-worker", ServerStatus.STOPPED,
            "ap-chuncheon-1", "VM.Test", 1, 6, null, null, null);

    private static Sidebar sidebar() {
        Sidebar s = new Sidebar();
        s.setInventory(List.of(new AccountInventory(ACCOUNT, List.of(RUNNING, STOPPED), null)));
        s.setCpu(Map.of("s1", 35.0));
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

    /** 글자가 같은 라벨 중 하나라도 화면에 보이도록 켜져 있는지(부모 포함). */
    private static boolean labelVisible(Container root, String text) {
        return all(root, JLabel.class).stream().anyMatch(l -> text.equals(l.getText()) && isVisibleChain(l, root));
    }

    private static boolean isVisibleChain(Component c, Container root) {
        for (Component x = c; x != null && x != root.getParent(); x = x.getParent()) {
            if (!x.isVisible()) {
                return false;
            }
        }
        return true;
    }

    @Test
    void 전체_모양에서는_CPU와_상태_글자와_리전이_보인다() {
        Sidebar s = sidebar();

        assertFalse(s.isCompact());
        assertTrue(labelVisible(s, "35%"));
        assertTrue(labelVisible(s, "정지됨"));
        assertTrue(labelVisible(s, "춘천"));
    }

    @Test
    void 좁은_모드에서는_이름만_남고_CPU와_상태_글자와_리전이_숨는다() {
        Sidebar s = sidebar();

        s.setCompact(true);

        assertTrue(s.isCompact());
        assertFalse(labelVisible(s, "35%"));
        assertFalse(labelVisible(s, "정지됨"));
        assertFalse(labelVisible(s, "춘천"));
        assertTrue(labelVisible(s, "discord-bot"), "이름은 남는다");
        assertTrue(labelVisible(s, "batch-worker"));
    }

    @Test
    void 좁은_모드에서는_하단_계정_추가가_아이콘만_남고_안내가_붙는다() {
        Sidebar s = sidebar();
        AbstractButton add = all(s, AbstractButton.class).stream()
                .filter(b -> "계정·서버 추가".equals(b.getText())).findFirst().orElseThrow();

        s.setCompact(true);
        assertEquals("", add.getText());
        assertEquals("계정·서버 추가", add.getToolTipText());

        s.setCompact(false);
        assertEquals("계정·서버 추가", add.getText());
    }

    @Test
    void 다시_넓히면_원래_모양으로_돌아온다() {
        Sidebar s = sidebar();
        s.setCompact(true);

        s.setCompact(false);

        assertTrue(labelVisible(s, "35%"));
        assertTrue(labelVisible(s, "춘천"));
    }

    @Test
    void 좁은_모드의_서버_행은_마우스를_올리면_이름과_상태를_안내한다() {
        Sidebar s = sidebar();
        s.setCompact(true);

        ServerListItem item = all(s, ServerListItem.class).stream()
                .filter(i -> i.server().id().equals("s1")).findFirst().orElseThrow();

        assertEquals("discord-bot · 실행 중 · CPU 35%", item.getToolTipText());
    }

    @Test
    void 좁은_모드에서_목록을_다시_만들어도_좁은_모드가_유지된다() {
        // CPU 값이 주기적으로 갱신되면 목록이 다시 만들어진다. 그때 CPU% 글자가 다시 나타나면 안 된다.
        Sidebar s = sidebar();
        s.setCompact(true);

        s.setCpu(Map.of("s1", 41.0));

        assertFalse(labelVisible(s, "41%"));
        assertTrue(labelVisible(s, "discord-bot"));
    }
}
