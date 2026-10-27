package com.infradesk.ui;

import com.infradesk.core.Account;
import com.infradesk.core.ProviderType;
import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.infradesk.ssh.Container;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 서버 화면: 컨테이너 탭과 로그 패널의 연결 규칙. 가짜 값만 쓴다. */
class ServerScreenTest {

    private static final Account ACCOUNT = new Account("acc-1", "계정 A", ProviderType.ORACLE, "test-region-1", Map.of());

    private static Server server(String id) {
        return new Server(id, "acc-1", "server-" + id, ServerStatus.RUNNING, "test-region-1", "VM.Test", 2, 12, null, null, null);
    }

    private static Container container(String name, boolean running) {
        // id는 서로 달라야 할 뿐이다. 항상 64자가 되도록 이름 뒤를 0으로 채운다(이름이 짧아도 길이가 모자라지 않는다).
        return new Container((name + "0".repeat(64)).substring(0, 64), name, name + ":1", running ? "running" : "exited",
                "", "", running ? 1.0 : -1, "", -1, "");
    }

    /** 요청이 나간 것을 기록하도록 연결한 서버 화면. */
    private static final class Fixture {
        final ServerScreen screen = new ServerScreen();
        final List<String> requests = new ArrayList<>();

        Fixture() {
            screen.logs().onReload((c, tail) -> requests.add(c.name()));
            screen.show(server("one"), ACCOUNT);
        }
    }

    @Test
    void 컨테이너_탭이_닫혀_있으면_목록이_와도_로그를_읽지_않는다() {
        Fixture f = new Fixture();

        f.screen.setContainers(List.of(container("web", true), container("db", true)), java.time.Instant.EPOCH);

        assertEquals(List.of(), f.requests, "서버를 고를 때마다 SSH 로그 조회가 생기면 안 된다");
        assertNull(f.screen.logs().selected());
    }

    @Test
    void 컨테이너_탭을_열면_첫_컨테이너의_로그를_바로_읽는다() {
        Fixture f = new Fixture();
        f.screen.setContainers(List.of(container("web", true), container("db", true)), java.time.Instant.EPOCH);

        f.screen.showContainersTab();

        assertEquals(List.of("web"), f.requests);
    }

    @Test
    void 탭이_열린_채로_목록이_오면_바로_읽는다() {
        Fixture f = new Fixture();
        f.screen.showContainersTab();                 // 목록이 아직 없을 때 탭을 연다
        assertEquals(List.of(), f.requests);

        f.screen.setContainers(List.of(container("web", true)), java.time.Instant.EPOCH);

        assertEquals(List.of("web"), f.requests);
    }

    @Test
    void 개요_탭으로_돌아갔다_다시_열어도_이미_고른_컨테이너는_다시_읽지_않는다() {
        Fixture f = new Fixture();
        f.screen.setContainers(List.of(container("web", true)), java.time.Instant.EPOCH);
        f.screen.showContainersTab();
        f.screen.showOverviewTab();

        f.screen.showContainersTab();

        assertEquals(List.of("web"), f.requests);
    }

    @Test
    void 서버가_바뀌면_이전_서버의_컨테이너_선택이_비워진다() {
        Fixture f = new Fixture();
        f.screen.setContainers(List.of(container("web", true)), java.time.Instant.EPOCH);
        f.screen.showContainersTab();

        f.screen.show(server("two"), ACCOUNT);

        assertNull(f.screen.logs().selected());
    }

    @Test
    void 컨테이너를_읽지_못하면_선택과_개수가_비워진다() {
        Fixture f = new Fixture();
        f.screen.setContainers(List.of(container("web", true)), java.time.Instant.EPOCH);
        f.screen.showContainersTab();

        f.screen.clearContainers();

        assertNull(f.screen.logs().selected());
    }
}
