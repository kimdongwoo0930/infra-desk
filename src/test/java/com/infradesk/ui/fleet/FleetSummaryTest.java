package com.infradesk.ui.fleet;

import com.infradesk.core.Account;
import com.infradesk.core.ProviderType;
import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.infradesk.service.AccountInventory;
import com.infradesk.service.FleetService.Reading;
import com.infradesk.ssh.Container;
import com.infradesk.ssh.DockerCommands.Listing;
import com.infradesk.ssh.HostFacts;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FleetSummaryTest {

    private static final Account A = new Account("a", "계정 A", ProviderType.ORACLE, "ap-seoul-1", Map.of());
    private static final Account B = new Account("b", "계정 B", ProviderType.ORACLE, "ap-seoul-1", Map.of());
    private static final Account MAC = new Account("m", "맥", ProviderType.SSH, "ssh", Map.of());

    private static Server server(String id, String account, ServerStatus status) {
        return new Server(id, account, id, status, "r", "shape", 1, 1, null, null, null);
    }

    private static Container container(boolean running) {
        return new Container("0123456789ab", "c", "img", running ? "running" : "exited", "", "", -1, "", -1, "");
    }

    private static Reading reading(String id, Container... containers) {
        return new Reading(id, Instant.EPOCH, new HostFacts(Duration.ZERO, "x", 1, 2, List.of()),
                new Listing(Listing.Status.OK, List.of(containers), null), null);
    }

    private final List<AccountInventory> inventory = List.of(
            new AccountInventory(A, List.of(server("s1", "a", ServerStatus.RUNNING), server("s2", "a", ServerStatus.STOPPED)), null),
            new AccountInventory(B, List.of(server("s3", "b", ServerStatus.RUNNING)), null),
            new AccountInventory(MAC, List.of(server("s4", "m", ServerStatus.RUNNING)), null));

    @Test
    void countsServersAccountsAndDirectHosts() {
        FleetSummary s = FleetSummary.of(inventory, Map.of(), id -> Optional.empty());
        assertEquals(4, s.servers());
        assertEquals(3, s.running());
        assertEquals(2, s.cloudAccounts());
        assertEquals(1, s.direct());
        assertEquals("서버 4대 · 클라우드 계정 2개 · 직접 연결 1대", s.subtitle());
    }

    @Test
    void subtitleOmitsWhatIsMissing() {
        FleetSummary onlyDirect = FleetSummary.of(List.of(inventory.get(2)), Map.of(), id -> Optional.empty());
        assertEquals("서버 1대 · 직접 연결 1대", onlyDirect.subtitle());
    }

    @Test
    void averageCpuCoversRunningServersWithAKnownValue() {
        // s2는 정지라 값이 있어도 빼고, s4는 값을 몰라 뺀다
        FleetSummary s = FleetSummary.of(inventory, Map.of("s1", 10.0, "s2", 90.0, "s3", 30.0), id -> Optional.empty());
        assertEquals(20, s.averageCpu(), 1e-9);
        assertEquals("20%", s.averageCpuText());
        assertEquals("—", FleetSummary.of(inventory, Map.of(), id -> Optional.empty()).averageCpuText());
    }

    @Test
    void containersAreCountedOnlyForServersThatWereRead() {
        FleetSummary none = FleetSummary.of(inventory, Map.of(), id -> Optional.empty());
        assertEquals("—", none.containersText());
        assertEquals("", none.containersSub());

        FleetSummary s = FleetSummary.of(inventory, Map.of(), id -> switch (id) {
            case "s1" -> Optional.of(reading(id, container(true), container(false)));
            case "s3" -> Optional.of(reading(id, container(true), container(true)));
            default -> Optional.empty();
        });
        assertEquals("3", s.containersText());
        assertEquals("/ 4개", s.containersSub());
    }
}
