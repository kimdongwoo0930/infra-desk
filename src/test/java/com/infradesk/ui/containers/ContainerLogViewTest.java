package com.infradesk.ui.containers;

import com.infradesk.demo.DemoData;
import com.infradesk.ssh.Container;

import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JTextField;
import javax.swing.JToggleButton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 로그 패널: 서버 없이, 받아 온 텍스트를 직접 넣어 가며 화면의 반응을 확인한다. 가짜 값만 쓴다. */
class ContainerLogViewTest {

    private static final Container WEB = container("a".repeat(64), "nginx", true);
    private static final Container APP = container("b".repeat(64), "discord-bot", true);
    private static final Container OLD = container("c".repeat(64), "watchtower", false);

    private static Container container(String id, String name, boolean running) {
        return new Container(id, name, name + ":1", running ? "running" : "exited", running ? "Up 1 day" : "Exited (0) 3 days ago",
                "", running ? 1.0 : -1, running ? "10MiB / 1GiB" : "", running ? 1.0 : -1, "");
    }

    /** 요청이 나간 것을 기록하는 보기. */
    private static final class Fixture {
        final ContainerLogView view = new ContainerLogView();
        final List<String> requests = new ArrayList<>();

        Fixture() {
            view.onReload((c, tail) -> requests.add(c.name() + ":" + tail));
            view.setContainers(List.of(WEB, APP, OLD));
        }
    }

    private static <T> List<T> all(java.awt.Container root, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component c : root.getComponents()) {
            if (type.isInstance(c)) {
                found.add(type.cast(c));
            }
            if (c instanceof java.awt.Container inner) {
                found.addAll(all(inner, type));
            }
        }
        return found;
    }

    private static JToggleButton autoRefresh(ContainerLogView v) {
        return all(v, JToggleButton.class).stream().filter(b -> "자동 새로고침".equals(b.getText())).findFirst().orElseThrow();
    }

    /** 구간 선택(전체/경고/오류)의 버튼 중 글자가 접두어로 시작하는 것. */
    private static AbstractButton level(ContainerLogView v, String prefix) {
        return all(v, JToggleButton.class).stream().filter(b -> b.getText().startsWith(prefix)).findFirst().orElseThrow();
    }

    @Test
    void 컨테이너를_고르면_그_컨테이너의_로그를_요청한다() {
        Fixture f = new Fixture();

        f.view.select(APP);

        assertEquals(List.of("discord-bot:200"), f.requests);
        assertEquals(APP, f.view.selected());
    }

    @Test
    void 받은_로그의_줄_수와_경고_오류_개수를_집계한다() {
        Fixture f = new Fixture();
        f.view.select(WEB);

        f.view.showLogs(WEB, DemoData.containerLog("nginx", true));

        // 데모 nginx 로그: 12줄, 경고 2(404 두 번), 오류 2(upstream [error], 502)
        assertEquals("보이는 줄 12 / 받은 줄 12 · 경고 2 · 오류 2", f.view.summaryText());
        assertEquals(12, f.view.visibleLines());
        assertEquals("경고 2", level(f.view, "경고").getText());
        assertEquals("오류 2", level(f.view, "오류").getText());
    }

    @Test
    void 경고만_또는_오류만_골라_볼_수_있다() {
        Fixture f = new Fixture();
        f.view.select(WEB);
        f.view.showLogs(WEB, DemoData.containerLog("nginx", true));

        level(f.view, "경고").doClick();
        assertEquals(2, f.view.visibleLines());

        level(f.view, "오류").doClick();
        assertEquals(2, f.view.visibleLines());

        level(f.view, "전체").doClick();
        assertEquals(12, f.view.visibleLines());
        // 받은 줄 수는 거르기와 상관없이 그대로다.
        assertTrue(f.view.summaryText().contains("받은 줄 12"));
    }

    @Test
    void 검색어를_넣으면_내용이_맞는_줄만_보인다() {
        Fixture f = new Fixture();
        f.view.select(WEB);
        f.view.showLogs(WEB, DemoData.containerLog("nginx", true));
        JTextField search = all(f.view, JTextField.class).getFirst();

        search.setText("HEALTH");   // 대소문자를 구분하지 않는다

        assertEquals(3, f.view.visibleLines());
        assertTrue(f.view.summaryText().startsWith("보이는 줄 3 / 받은 줄 12"));

        search.setText("");
        assertEquals(12, f.view.visibleLines());
    }

    @Test
    void 자동_새로고침은_기본이_꺼짐이고_실행_중인_컨테이너에서만_켤_수_있다() {
        Fixture f = new Fixture();

        f.view.select(WEB);
        assertFalse(autoRefresh(f.view).isSelected(), "기본은 꺼짐");
        assertTrue(autoRefresh(f.view).isEnabled());

        autoRefresh(f.view).doClick();
        assertTrue(autoRefresh(f.view).isSelected());

        // 멈춘 컨테이너로 바꾸면 꺼지고 눌리지 않는다
        f.view.select(OLD);
        assertFalse(autoRefresh(f.view).isSelected());
        assertFalse(autoRefresh(f.view).isEnabled());
    }

    @Test
    void 자동_새로고침이_꺼져_있으면_타이머가_울려도_읽지_않는다() {
        Fixture f = new Fixture();
        f.view.select(WEB);
        f.view.showLogs(WEB, DemoData.containerLog("nginx", true));
        f.requests.clear();

        f.view.autoRefreshTick();

        assertEquals(List.of(), f.requests);
    }

    @Test
    void 자동_새로고침은_표를_그대로_두고_조용히_다시_읽는다() {
        Fixture f = new Fixture();
        f.view.select(WEB);
        f.view.showLogs(WEB, DemoData.containerLog("nginx", true));
        autoRefresh(f.view).doClick();
        f.requests.clear();

        f.view.autoRefreshTick();

        assertEquals(List.of("nginx:200"), f.requests);
        assertTrue(f.view.showsTable(), "자동으로 읽을 때는 '불러오는 중' 화면으로 바꾸지 않는다(깜빡임 없음)");
        assertEquals(12, f.view.visibleLines());
    }

    @Test
    void 사용자가_시킨_읽기는_불러오는_중_화면을_보여_준다() {
        Fixture f = new Fixture();
        f.view.select(WEB);
        f.view.showLogs(WEB, DemoData.containerLog("nginx", true));
        assertTrue(f.view.showsTable());

        // 새로고침 버튼
        all(f.view, AbstractButton.class).stream().filter(b -> "로그 새로고침".equals(b.getToolTipText()))
                .findFirst().orElseThrow().doClick();

        assertFalse(f.view.showsTable());
    }

    @Test
    void 읽는_중에는_자동_새로고침이_요청을_쌓지_않는다() {
        Fixture f = new Fixture();
        f.view.select(WEB);
        f.view.showLogs(WEB, DemoData.containerLog("nginx", true));
        autoRefresh(f.view).doClick();
        f.requests.clear();

        f.view.autoRefreshTick();   // 요청을 보냈다
        f.view.autoRefreshTick();   // 아직 응답이 없다: 건너뛴다
        f.view.autoRefreshTick();

        assertEquals(List.of("nginx:200"), f.requests);

        f.view.showLogs(WEB, DemoData.containerLog("nginx", true));   // 응답이 왔다
        f.view.autoRefreshTick();

        assertEquals(List.of("nginx:200", "nginx:200"), f.requests);
    }

    @Test
    void 읽기에_실패하면_자동_새로고침이_스스로_꺼진다() {
        Fixture f = new Fixture();
        f.view.select(WEB);
        f.view.showLogs(WEB, DemoData.containerLog("nginx", true));
        autoRefresh(f.view).doClick();
        f.requests.clear();
        f.view.autoRefreshTick();

        f.view.showError("SSH 연결에 실패했어요");

        assertFalse(autoRefresh(f.view).isSelected(), "실패한 채로 10초마다 접속을 시도하면 안 된다");
        f.requests.clear();
        f.view.autoRefreshTick();
        assertEquals(List.of(), f.requests);
    }

    @Test
    void 멈춘_컨테이너를_보고_있으면_타이머가_울려도_읽지_않는다() {
        Fixture f = new Fixture();
        f.view.select(WEB);
        autoRefresh(f.view).doClick();
        f.view.select(OLD);
        f.requests.clear();

        f.view.autoRefreshTick();

        assertEquals(List.of(), f.requests);
    }

    @Test
    void 늦게_도착한_다른_컨테이너의_응답은_버린다() {
        Fixture f = new Fixture();
        f.view.select(WEB);
        f.view.select(APP);          // 그 사이 다른 컨테이너로 바꿨다

        f.view.showLogs(WEB, DemoData.containerLog("nginx", true));   // nginx의 응답이 뒤늦게 도착

        assertEquals(0, f.view.visibleLines());
    }

    @SuppressWarnings("unchecked")
    @Test
    void 줄_수를_바꾸면_그만큼_다시_요청한다() {
        Fixture f = new Fixture();
        f.view.select(WEB);
        f.requests.clear();
        JComboBox<Integer> tail = all(f.view, JComboBox.class).getFirst();

        tail.setSelectedItem(1000);

        assertEquals(List.of("nginx:1000"), f.requests);
        assertEquals(1000, f.view.tail());
    }

    @Test
    void 새로고침_목록에_보던_컨테이너가_있으면_선택을_유지하고_다시_요청하지_않는다() {
        Fixture f = new Fixture();
        f.view.select(WEB);
        f.requests.clear();

        f.view.setContainers(List.of(WEB, APP));

        assertEquals(WEB, f.view.selected());
        assertEquals(List.of(), f.requests);
    }

    @Test
    void 보던_컨테이너가_사라지면_선택이_풀린다() {
        Fixture f = new Fixture();
        f.view.select(WEB);

        f.view.setContainers(List.of(APP, OLD));

        assertNull(f.view.selected());
        assertEquals(0, f.view.visibleLines());
    }

    @Test
    void 고른_것이_없으면_첫_컨테이너를_골라_로그를_요청한다() {
        Fixture f = new Fixture();

        f.view.ensureSelection();

        assertEquals(WEB, f.view.selected());
        assertEquals(List.of("nginx:200"), f.requests);
    }

    @Test
    void 이미_고른_것이_있으면_자동_선택은_아무것도_하지_않는다() {
        Fixture f = new Fixture();
        f.view.select(APP);
        f.requests.clear();

        f.view.ensureSelection();

        assertEquals(APP, f.view.selected());
        assertEquals(List.of(), f.requests);
    }

    @Test
    void 컨테이너가_없으면_자동_선택도_없다() {
        ContainerLogView view = new ContainerLogView();
        List<String> requests = new ArrayList<>();
        view.onReload((c, tail) -> requests.add(c.name()));

        view.ensureSelection();

        assertNull(view.selected());
        assertEquals(List.of(), requests);
    }

    @Test
    void 컨테이너를_고를_때마다_선택을_알리고_목록_갱신만으로는_알리지_않는다() {
        Fixture f = new Fixture();
        List<String> selected = new ArrayList<>();
        f.view.onSelected(c -> selected.add(c.name()));

        f.view.select(WEB);
        f.view.select(APP);
        f.view.setContainers(List.of(WEB, APP, OLD));   // 주기적 갱신: 보던 컨테이너가 그대로

        assertEquals(List.of("nginx", "discord-bot"), selected);
    }

    @Test
    void 자동_선택도_선택으로_알린다() {
        // 컨테이너 탭을 열면 첫 컨테이너가 자동으로 골라진다: 표도 그 행을 강조해야 한다.
        Fixture f = new Fixture();
        List<String> selected = new ArrayList<>();
        f.view.onSelected(c -> selected.add(c.name()));

        f.view.ensureSelection();

        assertEquals(List.of("nginx"), selected);
    }

    @Test
    void 별도_창이_같은_목록으로_시작하도록_컨테이너_목록을_알려_준다() {
        Fixture f = new Fixture();

        assertEquals(List.of(WEB, APP, OLD), f.view.containers());
    }

    @Test
    void 별도_창_안에서는_별도_창_버튼을_숨길_수_있다() {
        Fixture f = new Fixture();
        AbstractButton open = all(f.view, AbstractButton.class).stream()
                .filter(b -> "별도 창으로 열기".equals(b.getToolTipText())).findFirst().orElseThrow();
        assertTrue(open.isVisible());

        f.view.setOpenWindowVisible(false);

        assertFalse(open.isVisible());
    }

    @Test
    void 별도_창을_열면_창마다_선택이_독립이다() {
        // 메인 화면과 별도 창이 각자 ContainerLogView를 가진다: 한쪽에서 고른 것이 다른 쪽에 영향을 주지 않는다.
        Fixture main = new Fixture();
        Fixture window = new Fixture();
        main.view.select(WEB);

        window.view.select(APP);

        assertEquals(WEB, main.view.selected());
        assertEquals(APP, window.view.selected());
        assertEquals(List.of("nginx:200"), main.requests);
        assertEquals(List.of("discord-bot:200"), window.requests);
    }

    @Test
    void 자동_새로고침_타이머는_켤_때_돌고_끄거나_닫으면_멈춘다() {
        Fixture f = new Fixture();
        f.view.select(WEB);
        assertFalse(f.view.autoRefreshRunning(), "기본은 꺼짐: 타이머도 돌지 않는다");

        autoRefresh(f.view).doClick();
        assertTrue(f.view.autoRefreshRunning());

        autoRefresh(f.view).doClick();
        assertFalse(f.view.autoRefreshRunning());

        // 켠 채로 별도 창을 닫으면(close) 타이머가 멈춘다
        autoRefresh(f.view).doClick();
        assertTrue(f.view.autoRefreshRunning());
        f.view.close();
        assertFalse(f.view.autoRefreshRunning(), "닫힌 창의 타이머가 남으면 안 된다");
    }

    @Test
    void 멈춘_컨테이너로_바꾸면_타이머가_멈춘다() {
        Fixture f = new Fixture();
        f.view.select(WEB);
        autoRefresh(f.view).doClick();
        assertTrue(f.view.autoRefreshRunning());

        f.view.select(OLD);

        assertFalse(f.view.autoRefreshRunning());
    }

    @Test
    void 아무것도_고르지_않았으면_새로고침을_눌러도_요청하지_않는다() {
        Fixture f = new Fixture();
        // 새로고침 버튼: 도구 막대 버튼 중 툴팁이 "로그 새로고침"인 것
        AbstractButton refresh = all(f.view, AbstractButton.class).stream()
                .filter(b -> "로그 새로고침".equals(b.getToolTipText())).findFirst().orElseThrow();

        refresh.doClick();

        assertEquals(List.of(), f.requests);
    }
}
