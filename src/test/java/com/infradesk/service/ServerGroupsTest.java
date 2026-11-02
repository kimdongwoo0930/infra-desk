package com.infradesk.service;

import com.infradesk.core.Account;
import com.infradesk.core.ProviderType;
import com.infradesk.core.Server;
import com.infradesk.core.ServerGrouping;
import com.infradesk.core.ServerStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerGroupsTest {

    private static final Account CLOUD = new Account("a1", "계정 A", ProviderType.ORACLE, "ap-chuncheon-1", Map.of());
    private static final Account DIRECT = new Account("a2", "맥미니", ProviderType.SSH, "ssh", Map.of());

    private static Server server(String id, ServerStatus status) {
        return new Server(id, "a1", id, status, "r", "shape", 1, 1, null, null, null);
    }

    private static List<AccountInventory> inventory() {
        return List.of(
                new AccountInventory(CLOUD, List.of(server("web", ServerStatus.RUNNING), server("db", ServerStatus.STOPPED)), null),
                new AccountInventory(DIRECT, List.of(server("mac", ServerStatus.RUNNING), server("nas", ServerStatus.UNREACHABLE)), null));
    }

    private static List<String> names(ServerGroups.Group group) {
        return group.members().stream().map(m -> m.server().name()).toList();
    }

    @Test
    void byAccountMakesOneGroupPerAccountInOrder() {
        List<ServerGroups.Group> groups = ServerGroups.of(inventory(), ServerGrouping.ACCOUNT, s -> true, false);

        assertEquals(2, groups.size());
        assertEquals(List.of("web", "db"), names(groups.get(0)));
        assertEquals(List.of("mac", "nas"), names(groups.get(1)));
        assertNull(groups.get(0).title());   // 계정별은 이름 대신 계정을 돌려준다
        assertEquals(CLOUD, groups.get(0).account().account());
    }

    @Test
    void anAccountWithoutMatchesIsKeptOnlyWhenAskedTo() {
        List<AccountInventory> inventory = List.of(new AccountInventory(CLOUD, List.of(), null),
                new AccountInventory(DIRECT, List.of(server("mac", ServerStatus.RUNNING)), null));

        assertEquals(2, ServerGroups.of(inventory, ServerGrouping.ACCOUNT, s -> true, true).size());
        assertEquals(1, ServerGroups.of(inventory, ServerGrouping.ACCOUNT, s -> true, false).size());
    }

    @Test
    void anAccountThatFailedToLoadKeepsItsErrorForTheHeader() {
        List<AccountInventory> inventory = List.of(new AccountInventory(CLOUD, List.of(), "연결하지 못했어요"));

        ServerGroups.Group group = ServerGroups.of(inventory, ServerGrouping.ACCOUNT, s -> true, true).getFirst();

        assertTrue(group.account().failed());
        assertTrue(group.members().isEmpty());
    }

    @Test
    void byStatusSeparatesRunningStoppedAndTheRest() {
        List<ServerGroups.Group> groups = ServerGroups.of(inventory(), ServerGrouping.STATUS, s -> true, true);

        assertEquals(List.of("실행 중", "정지", "그 밖"), groups.stream().map(ServerGroups.Group::title).toList());
        assertEquals(List.of("web", "mac"), names(groups.get(0)));   // 계정을 넘나들며 모은다
        assertEquals(List.of("db"), names(groups.get(1)));
        assertEquals(List.of("nas"), names(groups.get(2)));           // 응답 없음은 정지가 아니라 "그 밖"
    }

    @Test
    void byStatusLeavesOutEmptyGroups() {
        List<AccountInventory> inventory = List.of(new AccountInventory(CLOUD, List.of(server("web", ServerStatus.RUNNING)), null));

        List<ServerGroups.Group> groups = ServerGroups.of(inventory, ServerGrouping.STATUS, s -> true, true);

        assertEquals(List.of("실행 중"), groups.stream().map(ServerGroups.Group::title).toList());
    }

    @Test
    void transitionalStatesFallIntoTheLastGroup() {
        List<AccountInventory> inventory = List.of(new AccountInventory(CLOUD, List.of(
                server("a", ServerStatus.STARTING), server("b", ServerStatus.STOPPING), server("c", ServerStatus.UNKNOWN)), null));

        List<ServerGroups.Group> groups = ServerGroups.of(inventory, ServerGrouping.STATUS, s -> true, false);

        assertEquals(1, groups.size());
        assertEquals("그 밖", groups.getFirst().title());
        assertEquals(3, groups.getFirst().members().size());
    }

    @Test
    void noGroupingGivesOneUntitledGroupWithEveryServer() {
        List<ServerGroups.Group> groups = ServerGroups.of(inventory(), ServerGrouping.NONE, s -> true, true);

        assertEquals(1, groups.size());
        assertNull(groups.getFirst().title());
        assertNull(groups.getFirst().account());
        assertEquals(List.of("web", "db", "mac", "nas"), names(groups.getFirst()));
    }

    @Test
    void noGroupingWithNothingToShowGivesNoGroups() {
        assertTrue(ServerGroups.of(inventory(), ServerGrouping.NONE, s -> false, true).isEmpty());
    }

    @Test
    void theFilterAppliesToEveryGrouping() {
        for (ServerGrouping grouping : ServerGrouping.values()) {
            int total = ServerGroups.of(inventory(), grouping, s -> s.status() == ServerStatus.RUNNING, false).stream()
                    .mapToInt(g -> g.members().size()).sum();
            assertEquals(2, total, grouping.name());
        }
    }

    @Test
    void eachMemberKnowsItsOwnAccount() {
        ServerGroups.Group all = ServerGroups.of(inventory(), ServerGrouping.NONE, s -> true, false).getFirst();

        assertEquals(CLOUD, all.members().get(0).inventory().account());
        assertEquals(DIRECT, all.members().get(2).inventory().account());
    }

    @Test
    void accountTitleDropsTheCloudSuffix() {
        assertTrue(ServerGroups.accountTitle(CLOUD).endsWith(" · 계정 A"));
        assertTrue(!ServerGroups.accountTitle(CLOUD).contains("Cloud"));
    }
}
