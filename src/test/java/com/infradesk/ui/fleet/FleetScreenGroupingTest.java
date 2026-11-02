package com.infradesk.ui.fleet;

import com.infradesk.core.Account;
import com.infradesk.core.ProviderType;
import com.infradesk.core.Server;
import com.infradesk.core.ServerGrouping;
import com.infradesk.core.ServerStatus;
import com.infradesk.service.AccountInventory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FleetScreenGroupingTest {

    private static final Account CLOUD = new Account("a1", "계정 A", ProviderType.ORACLE, "ap-chuncheon-1", Map.of());
    private static final Account DIRECT = new Account("a2", "맥미니", ProviderType.SSH, "ssh", Map.of());

    private static Server server(String name, ServerStatus status) {
        return new Server(name, "a1", name, status, "r", "shape", 1, 1, null, null, null);
    }

    private static FleetScreen screen() {
        FleetScreen screen = new FleetScreen();
        screen.setData(List.of(
                new AccountInventory(CLOUD, List.of(server("web", ServerStatus.RUNNING), server("db", ServerStatus.STOPPED)), null),
                new AccountInventory(DIRECT, List.of(server("mac", ServerStatus.RUNNING)), null)),
                Map.of(), id -> Optional.empty(), id -> false);
        return screen;
    }

    private static List<String> cardNames(FleetScreen screen) {
        return screen.cards().stream().map(c -> c.server().name()).toList();
    }

    @Test
    void byAccountIsTheStartingWayAndTitlesEachAccount() {
        FleetScreen screen = screen();

        assertEquals(2, screen.sectionTitles().size());
        assertTrue(screen.sectionTitles().get(0).endsWith("계정 A"));
        assertTrue(screen.sectionTitles().get(1).endsWith("맥미니"));
        assertEquals(List.of("web", "db", "mac"), cardNames(screen));
    }

    @Test
    void byStatusGathersServersFromEveryAccount() {
        FleetScreen screen = screen();

        screen.setGrouping(ServerGrouping.STATUS);

        assertEquals(List.of("실행 중", "정지"), screen.sectionTitles());
        assertEquals(List.of("web", "mac", "db"), cardNames(screen));
    }

    @Test
    void withoutGroupingThereAreNoTitlesButEveryCardIsShown() {
        FleetScreen screen = screen();

        screen.setGrouping(ServerGrouping.NONE);

        assertTrue(screen.sectionTitles().isEmpty());
        assertEquals(List.of("web", "db", "mac"), cardNames(screen));
    }

    @Test
    void settingTheGroupingFromOutsideDoesNotCountAsTheUserPickingIt() {
        FleetScreen screen = screen();
        List<ServerGrouping> picked = new ArrayList<>();
        screen.onGroupingPicked(picked::add);

        screen.setGrouping(ServerGrouping.STATUS);

        assertTrue(picked.isEmpty(), "설정이 바꾼 값을 다시 저장하려고 되돌려 보내면 안 된다");
    }
}
