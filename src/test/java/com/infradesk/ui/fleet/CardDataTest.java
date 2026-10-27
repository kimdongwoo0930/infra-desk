package com.infradesk.ui.fleet;

import com.infradesk.core.Account;
import com.infradesk.core.ProviderType;
import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardDataTest {

    private static final Account CLOUD = new Account("a1", "계정 A", ProviderType.ORACLE, "ap-chuncheon-1", Map.of());
    private static final Account DIRECT = new Account("a2", "맥미니", ProviderType.SSH, "ssh", Map.of());

    private static Server server(ServerStatus status) {
        return new Server("s1", "a1", "discord-bot", status, "ap-chuncheon-1", "VM.Standard.A1.Flex", 2, 12, null, null, null);
    }

    private static Container container(String name, boolean running) {
        return new Container("0123456789ab", name, "img", running ? "running" : "exited", "", "", -1, "", -1, "");
    }

    private static Reading ok(HostFacts facts, Listing containers) {
        return new Reading("s1", Instant.EPOCH, facts, containers, null);
    }

    private static final HostFacts FACTS = new HostFacts(Duration.ofDays(9).plusHours(3), "Ubuntu 22.04", 42, 100,
            List.of(22), 4, 1000, 450);

    @Test
    void cloudMetaNamesAccountRegionShapeAndCpus() {
        assertEquals("계정 A · 춘천 · A1.Flex · 2 OCPU", CardData.meta(server(ServerStatus.RUNNING), CLOUD, null));
    }

    @Test
    void directMetaShowsTheOsOnceRead() {
        Server mac = new Server("s2", "a2", "mac-mini", ServerStatus.RUNNING, "ssh", "h:22", 0, 0, null, null, null);
        assertEquals("직접 연결 · SSH", CardData.meta(mac, DIRECT, null));
        assertEquals("직접 연결 · macOS 15 · SSH", CardData.meta(mac, DIRECT,
                new HostFacts(null, "macOS 15", -1, -1, List.of())));
    }

    @Test
    void runningCardCombinesCloudCpuWithSshValues() {
        Listing listing = new Listing(Listing.Status.OK,
                List.of(container("a", true), container("b", true), container("c", false)), null);
        CardData card = CardData.of(server(ServerStatus.RUNNING), CLOUD, 24.0, Optional.of(ok(FACTS, listing)), true);

        assertTrue(card.running());
        assertEquals(24, card.cpu(), 1e-9);
        assertEquals(45, card.memory(), 1e-9);
        assertEquals(42, card.disk(), 1e-9);
        assertEquals("컨테이너 2/3", card.containers());
        assertEquals(1, card.stopped());
        assertEquals("업타임 9일", card.uptime());
        assertEquals("", card.note());
    }

    @Test
    void valuesNotReadYetAreUnknownWithAnExplanation() {
        CardData notConfigured = CardData.of(server(ServerStatus.RUNNING), CLOUD, 10.0, Optional.empty(), false);
        assertEquals(10, notConfigured.cpu(), 1e-9, "CPU는 클라우드 지표라 SSH 없이도 보인다");
        assertEquals(CardData.UNKNOWN, notConfigured.memory(), 1e-9);
        assertTrue(notConfigured.note().contains("SSH"));

        CardData pending = CardData.of(server(ServerStatus.RUNNING), CLOUD, null, Optional.empty(), true);
        assertEquals(CardData.UNKNOWN, pending.cpu(), 1e-9);
        assertTrue(pending.note().contains("읽는 중"));

        CardData failed = CardData.of(server(ServerStatus.RUNNING), CLOUD, null,
                Optional.of(new Reading("s1", Instant.EPOCH, null, null, "접속 실패")), true);
        assertEquals("읽지 못했어요: 접속 실패", failed.note());
    }

    @Test
    void stoppedServerShowsAGuideAndAStartButtonOnlyWhenPowerCanBeControlled() {
        CardData stopped = CardData.of(server(ServerStatus.STOPPED), CLOUD, null, Optional.empty(), true);
        assertFalse(stopped.running());
        assertTrue(stopped.canStart());
        assertTrue(stopped.note().startsWith("정지됨"));

        Server direct = new Server("s2", "a2", "mac-mini", ServerStatus.STOPPED, "ssh", "h:22", 0, 0, null, null, null);
        assertFalse(CardData.of(direct, DIRECT, null, Optional.empty(), true).canStart());
        assertFalse(CardData.of(server(ServerStatus.STARTING), CLOUD, null, Optional.empty(), true).canStart(),
                "시작하는 중에는 또 시작할 수 없다");
    }

    @Test
    void containerTextCoversEveryListingState() {
        assertEquals("컨테이너 —", CardData.containersText(null));
        assertEquals("컨테이너 없음", CardData.containersText(new Listing(Listing.Status.OK, List.of(), null)));
        assertEquals("Docker 없음", CardData.containersText(new Listing(Listing.Status.NO_DOCKER, List.of(), "x")));
        assertEquals("Docker 권한 없음", CardData.containersText(new Listing(Listing.Status.NO_ACCESS, List.of(), "x")));
        assertEquals("컨테이너 —", CardData.containersText(new Listing(Listing.Status.ERROR, List.of(), "x")));
    }

    @Test
    void uptimeUsesTheLargestUnit() {
        assertEquals("9일", CardData.uptimeText(Duration.ofDays(9).plusHours(23)));
        assertEquals("3시간", CardData.uptimeText(Duration.ofHours(3).plusMinutes(59)));
        assertEquals("1분", CardData.uptimeText(Duration.ofSeconds(5)));
    }
}
