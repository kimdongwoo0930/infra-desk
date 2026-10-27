package com.infradesk.ui;

import com.infradesk.core.Account;
import com.infradesk.core.ProviderType;
import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.infradesk.service.ServerAction;
import com.infradesk.ui.components.ComingSoon;

import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 서버 화면 머리의 버튼 규칙: 서버 상태에 따라 어떤 버튼이 보이고 눌리는지.
 * (이 규칙은 {@code ServerDetailPanel} 안에 있다가 {@link ServerHeader}로 옮겨 왔다. 옮기고도 같은 동작인지 고정한다.)
 * 가짜 값만 쓴다.
 */
class ServerHeaderTest {

    private static final Account CLOUD = new Account("acc-1", "계정 A", ProviderType.ORACLE, "test-region-1", Map.of());
    private static final Account DIRECT = new Account("acc-2", "집 서버", ProviderType.SSH, "", Map.of());

    private static Server server(ServerStatus status) {
        return new Server("srv-1", "acc-1", "test-server", status, "test-region-1", "VM.Test", 2, 12, null, null, null);
    }

    private static ServerHeader header(Account account, ServerStatus status) {
        ServerHeader h = new ServerHeader();
        h.show(server(status), account);
        return h;
    }

    /** 컴포넌트 트리에서 글자가 같은 버튼을 찾는다. */
    private static JButton button(Container root, String text) {
        for (JButton b : buttons(root)) {
            if (text.equals(b.getText())) {
                return b;
            }
        }
        throw new AssertionError("버튼을 찾을 수 없다: " + text);
    }

    private static List<JButton> buttons(Container root) {
        List<JButton> found = new ArrayList<>();
        for (Component c : root.getComponents()) {
            if (c instanceof JButton b) {
                found.add(b);
            }
            if (c instanceof Container inner) {
                found.addAll(buttons(inner));
            }
        }
        return found;
    }

    @Test
    void 실행_중인_클라우드_서버는_SSH_재부팅_정지가_눌리고_시작은_숨는다() {
        ServerHeader h = header(CLOUD, ServerStatus.RUNNING);

        assertTrue(button(h, "SSH 열기").isEnabled());
        assertTrue(button(h, "재부팅").isEnabled());
        assertTrue(button(h, "정지").isVisible());
        assertTrue(button(h, "정지").isEnabled());
        assertFalse(button(h, "시작").isVisible());
    }

    @Test
    void 정지된_서버는_시작이_보이고_정지는_숨고_SSH는_막힌다() {
        ServerHeader h = header(CLOUD, ServerStatus.STOPPED);

        assertTrue(button(h, "시작").isVisible());
        assertTrue(button(h, "시작").isEnabled());
        assertFalse(button(h, "정지").isVisible());
        assertFalse(button(h, "SSH 열기").isEnabled());
        // 재부팅은 실행 중일 때만 누를 수 있다.
        assertFalse(button(h, "재부팅").isEnabled());
    }

    @Test
    void 요청을_보내는_동안은_전원_버튼이_모두_막히고_끝나면_풀린다() {
        ServerHeader h = header(CLOUD, ServerStatus.RUNNING);

        h.setBusy(true, "정지 요청을 보내는 중…");
        assertFalse(button(h, "정지").isEnabled());
        assertFalse(button(h, "재부팅").isEnabled());

        h.setBusy(false, null);
        assertTrue(button(h, "정지").isEnabled());
        assertTrue(button(h, "재부팅").isEnabled());
    }

    @Test
    void 직접_연결_기기는_전원_버튼이_보이지_않고_SSH만_눌린다() {
        ServerHeader h = header(DIRECT, ServerStatus.RUNNING);

        assertFalse(button(h, "재부팅").isVisible());
        assertFalse(button(h, "정지").isVisible());
        assertFalse(button(h, "시작").isVisible());
        assertTrue(button(h, "SSH 열기").isEnabled());
    }

    @Test
    void 버튼을_누르면_해당_동작을_알린다() {
        List<ServerAction> actions = new ArrayList<>();
        List<String> ssh = new ArrayList<>();

        ServerHeader running = header(CLOUD, ServerStatus.RUNNING);
        running.onAction(actions::add);
        running.onSsh(() -> ssh.add("ssh"));
        button(running, "정지").doClick();
        button(running, "재부팅").doClick();
        button(running, "SSH 열기").doClick();

        ServerHeader stopped = header(CLOUD, ServerStatus.STOPPED);
        stopped.onAction(actions::add);
        button(stopped, "시작").doClick();

        assertEquals(List.of(ServerAction.STOP, ServerAction.REBOOT, ServerAction.START), actions);
        assertEquals(List.of("ssh"), ssh);
    }

    @Test
    void 기능이_없는_별과_더보기는_비활성이고_준비_중_안내가_붙는다() {
        ServerHeader h = header(CLOUD, ServerStatus.RUNNING);

        List<JButton> comingSoon = buttons(h).stream().filter(b -> ComingSoon.TOOLTIP.equals(b.getToolTipText())).toList();

        assertEquals(2, comingSoon.size(), "즐겨찾기 별과 [⋯]");
        assertTrue(comingSoon.stream().noneMatch(JButton::isEnabled));
    }
}
