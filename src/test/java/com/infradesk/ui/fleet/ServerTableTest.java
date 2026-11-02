package com.infradesk.ui.fleet;

import com.infradesk.core.Account;
import com.infradesk.core.ProviderType;
import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerTableTest {

    private static final Account CLOUD = new Account("a1", "계정 A", ProviderType.ORACLE, "ap-chuncheon-1", Map.of());

    private static CardData card(String name, ServerStatus status) {
        Server server = new Server(name, "a1", name, status, "ap-chuncheon-1", "VM.Standard.A1.Flex", 2, 12, null, null, null);
        return CardData.of(server, CLOUD, 35.0, Optional.empty(), false);
    }

    private static ServerTable table() {
        return new ServerTable(List.of(card("discord-bot", ServerStatus.RUNNING), card("batch-worker", ServerStatus.STOPPED)),
                s -> { }, (s, tab) -> { }, s -> { }, false);
    }

    private static int levelAt(ServerTable table, int width) {
        table.setSize(width, 400);
        table.doLayout();
        return table.level();
    }

    // ── 단계 계산 ───────────────────────────────────────────────

    @Test
    void eachLevelNeedsLessWidthThanTheOneBefore() {
        int name = 110;
        for (int level = ServerTable.LEVEL_FULL; level < ServerTable.LEVEL_NO_EXTRAS; level++) {
            assertTrue(ServerTable.requiredWidth(level + 1, name) < ServerTable.requiredWidth(level, name),
                    "단계 " + (level + 1) + "는 단계 " + level + "보다 좁은 폭에서 그려져야 한다");
        }
    }

    @Test
    void aWideTableShowsEverythingAndANarrowOneDropsThingsInOrder() {
        int name = 110;
        assertEquals(ServerTable.LEVEL_FULL, ServerTable.levelFor(2000, name));
        assertEquals(ServerTable.LEVEL_NO_BARS,
                ServerTable.levelFor(ServerTable.requiredWidth(ServerTable.LEVEL_FULL, name) - 1, name));
        assertEquals(ServerTable.LEVEL_NO_META,
                ServerTable.levelFor(ServerTable.requiredWidth(ServerTable.LEVEL_NO_BARS, name) - 1, name));
        assertEquals(ServerTable.LEVEL_NO_EXTRAS,
                ServerTable.levelFor(ServerTable.requiredWidth(ServerTable.LEVEL_NO_META, name) - 1, name));
        assertEquals(ServerTable.LEVEL_NO_EXTRAS, ServerTable.levelFor(100, name));
    }

    @Test
    void aLongerServerNameNeedsMoreWidthBeforeAnythingIsDropped() {
        assertTrue(ServerTable.requiredWidth(ServerTable.LEVEL_NO_META, 300) > ServerTable.requiredWidth(ServerTable.LEVEL_NO_META, 110));
    }

    @Test
    void theSameWidthGivesTheSameLevelWhateverTheHistory() {
        assertEquals(ServerTable.levelFor(900, 110), ServerTable.levelFor(900, 110));
    }

    // ── 실제 배치 ───────────────────────────────────────────────

    @Test
    void theTableFollowsItsWidthDownAndBackUp() {
        ServerTable table = table();

        assertEquals(ServerTable.LEVEL_FULL, levelAt(table, 1400));
        int narrow = levelAt(table, 700);
        assertTrue(narrow > ServerTable.LEVEL_FULL, "좁으면 막대부터 줄어야 한다");
        assertEquals(ServerTable.LEVEL_FULL, levelAt(table, 1400), "다시 넓어지면 원래대로 돌아와야 한다");
    }

    @Test
    void theTableDoesNotChangeLevelBeforeItHasAWidth() {
        assertEquals(ServerTable.LEVEL_FULL, table().level());
    }

    @Test
    void theListShowsTheValuesItWasGiven() {
        assertEquals(List.of("discord-bot", "batch-worker"), table().data().stream().map(c -> c.server().name()).toList());
    }
}
