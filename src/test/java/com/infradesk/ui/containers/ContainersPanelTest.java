package com.infradesk.ui.containers;

import com.infradesk.ssh.Container;
import com.infradesk.ssh.DockerCommands;
import com.infradesk.ui.components.FilterChip;
import com.infradesk.ui.containers.ContainerRowActions.RowAction;

import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JTextField;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContainersPanelTest {

    private static final Container WEB = container("web", "nginx:1.27", true);
    private static final Container DB = container("db", "mysql:8.4", true);
    private static final Container CACHE = container("cache", "redis:7", true);
    private static final Container OLD = container("old", "containrrr/watchtower", false);

    private static Container container(String name, String image, boolean running) {
        return new Container((name + "0".repeat(64)).substring(0, 64), name, image, running ? "running" : "exited",
                running ? "Up 1 day" : "Exited (0) 3 days ago", "", running ? 5.0 : -1, running ? "10MiB / 1GiB" : "", -1, "");
    }

    private static ContainersPanel panel() {
        ContainersPanel p = new ContainersPanel();
        p.showContainers(List.of(WEB, DB, CACHE, OLD), "docker ps · 10:00:00 기준");
        return p;
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

    private static FilterChip chip(ContainersPanel p, String label) {
        return all(p, FilterChip.class).stream().filter(c -> label.equals(c.getText())).findFirst().orElseThrow();
    }

    @Test
    void compactsPortMappings() {
        assertEquals("80→80, 443→443", ContainersPanel.compactPorts("0.0.0.0:80->80/tcp, :::80->80/tcp, 0.0.0.0:443->443/tcp, :::443->443/tcp"));
        assertEquals("6379", ContainersPanel.compactPorts("6379/tcp"));
    }

    @Test
    void 칩에_전체_실행_중_멈춤_개수가_보인다() {
        ContainersPanel p = panel();

        assertEquals(4, chip(p, "전체").getCount());
        assertEquals(3, chip(p, "실행 중").getCount());
        assertEquals(1, chip(p, "멈춤").getCount());
    }

    @Test
    void 칩을_누르면_그_상태의_컨테이너만_보인다() {
        ContainersPanel p = panel();
        assertEquals(4, p.visibleRows());

        chip(p, "실행 중").doClick();
        assertEquals(3, p.visibleRows());

        chip(p, "멈춤").doClick();
        assertEquals(1, p.visibleRows());

        chip(p, "전체").doClick();
        assertEquals(4, p.visibleRows());
    }

    @Test
    void 검색어는_이름이나_이미지에_들어_있으면_맞는다() {
        ContainersPanel p = panel();
        JTextField search = all(p, JTextField.class).getFirst();

        search.setText("MYSQL");   // 이미지 이름, 대소문자 구분 없음
        assertEquals(1, p.visibleRows());

        search.setText("e");        // 이름이나 이미지에 e가 있는 것: web, cache, old(이미지 watchtower). db(mysql:8.4)는 없다
        assertEquals(3, p.visibleRows());

        search.setText("없는이름");
        assertEquals(0, p.visibleRows());

        search.setText("");
        assertEquals(4, p.visibleRows());
    }

    @Test
    void 칩과_검색어는_함께_적용된다() {
        ContainersPanel p = panel();
        chip(p, "실행 중").doClick();

        all(p, JTextField.class).getFirst().setText("redis");

        assertEquals(1, p.visibleRows());
    }

    @Test
    void 작업_아이콘을_누르면_해당_동작을_알린다() {
        ContainersPanel p = panel();
        List<String> events = new ArrayList<>();
        p.onShell((c, console) -> events.add("shell:" + c.name() + ":" + (console == null ? "-" : console.name())));
        p.onAction((c, a) -> events.add(a + ":" + c.name()));

        p.perform(WEB, RowAction.SHELL);
        p.perform(DB, RowAction.CONSOLE);
        p.perform(WEB, RowAction.RESTART);
        p.perform(WEB, RowAction.STOP);
        p.perform(OLD, RowAction.START);

        assertEquals(List.of("shell:web:-", "shell:db:" + DockerCommands.Console.MYSQL.name(),
                "RESTART:web", "STOP:web", "START:old"), events);
    }

    @Test
    void 요청_중에는_작업_아이콘을_눌러도_알리지_않는다() {
        ContainersPanel p = panel();
        List<String> events = new ArrayList<>();
        p.onAction((c, a) -> events.add(a + ":" + c.name()));

        p.setBusy(true, "불러오는 중…");
        p.perform(WEB, RowAction.STOP);
        assertEquals(List.of(), events);

        p.setBusy(false, null);
        p.perform(WEB, RowAction.STOP);
        assertEquals(List.of("STOP:web"), events);
    }

    @Test
    void 사용자가_행을_고르면_그_컨테이너를_알린다() {
        ContainersPanel p = panel();
        List<String> selected = new ArrayList<>();
        p.onSelect(c -> selected.add(c.name()));

        p.selectRowAsUser(1);   // 표는 실행 중인 것이 앞에 오지 않고 받은 순서 그대로다: web, db, cache, old

        assertEquals(List.of("db"), selected);
    }

    @Test
    void 코드가_행을_강조할_때는_선택을_알리지_않는다() {
        // 아래 로그에서 칩을 골라 표를 따라가게 할 때, 표가 다시 "골랐다"고 알리면 서로를 무한히 부르게 된다.
        ContainersPanel p = panel();
        List<String> selected = new ArrayList<>();
        p.onSelect(c -> selected.add(c.name()));

        p.highlight(CACHE);

        assertEquals(List.of(), selected);
        assertEquals("cache", p.highlighted().orElseThrow().name());
    }

    @Test
    void 거르기_때문에_보이지_않는_컨테이너를_강조하면_강조가_풀린다() {
        ContainersPanel p = panel();
        p.highlight(OLD);
        chip(p, "실행 중").doClick();   // 멈춘 old는 보이지 않는다

        p.highlight(OLD);

        assertEquals(java.util.Optional.empty(), p.highlighted());
    }

    @Test
    void 목록이_갱신되어도_고른_행이_유지되고_다시_알리지_않는다() {
        ContainersPanel p = panel();
        p.selectRowAsUser(2);
        List<String> selected = new ArrayList<>();
        p.onSelect(c -> selected.add(c.name()));

        p.showContainers(List.of(WEB, DB, CACHE, OLD), "docker ps · 10:00:30 기준");   // 주기적 갱신

        assertEquals("cache", p.highlighted().orElseThrow().name());
        assertEquals(List.of(), selected, "갱신 때문에 로그가 다시 읽히면 안 된다");
    }

    @Test
    void 메시지를_보이면_표와_칩_개수가_비워진다() {
        ContainersPanel p = panel();

        p.showMessage("서버가 실행 중일 때 표시돼요", null);

        assertEquals(0, p.visibleRows());
        assertEquals(0, chip(p, "전체").getCount());
    }
}
