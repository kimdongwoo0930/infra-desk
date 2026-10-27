package com.infradesk.service;

import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.infradesk.ssh.DockerCommands;
import com.infradesk.ssh.HostFacts;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FleetServiceTest {

    private static final DockerCommands.Listing NO_CONTAINERS =
            new DockerCommands.Listing(DockerCommands.Listing.Status.OK, List.of(), null);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-20T10:00:00Z"));

    private static Server server(String id, ServerStatus status) {
        return new Server(id, "acct", id, status, "r", "shape", 1, 1, null, null, null);
    }

    /** 가짜 읽기: 읽은 서버 이름을 기록하고, 동시에 몇 대가 읽는 중이었는지 센다. */
    private static final class FakeReader implements FleetService.Reader {
        final Set<String> unreadable = Collections.synchronizedSet(new java.util.HashSet<>());
        final Set<String> failing = Collections.synchronizedSet(new java.util.HashSet<>());
        final List<String> read = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        volatile long holdMillis;

        @Override
        public boolean readable(Server server) {
            return !unreadable.contains(server.id());
        }

        @Override
        public HostFacts facts(Server server) {
            int now = running.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(holdMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                running.decrementAndGet();
            }
            read.add(server.id());
            if (failing.contains(server.id())) {
                throw new IllegalStateException("접속 실패");
            }
            return new HostFacts(Duration.ofDays(1), "Linux", 10, 100, List.of());
        }

        @Override
        public DockerCommands.Listing containers(Server server) {
            return NO_CONTAINERS;
        }
    }

    private static List<FleetService.Reading> readAll(FleetService fleet, List<Server> servers, boolean force) throws Exception {
        List<FleetService.Reading> got = Collections.synchronizedList(new ArrayList<>());
        int started = fleet.read(servers, force, got::add);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (got.size() < started && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(started, got.size(), "시간 안에 다 읽어야 한다");
        return got;
    }

    @Test
    void readsOnlyTwoServersAtATime() throws Exception {
        FakeReader reader = new FakeReader();
        reader.holdMillis = 40;
        FleetService fleet = new FleetService(reader, clock);
        List<Server> servers = List.of(server("a", ServerStatus.RUNNING), server("b", ServerStatus.RUNNING),
                server("c", ServerStatus.RUNNING), server("d", ServerStatus.RUNNING), server("e", ServerStatus.RUNNING));

        readAll(fleet, servers, false);

        assertEquals(5, reader.read.size());
        assertEquals(FleetService.PARALLEL, reader.peak.get(), "동시에 2대까지만 읽는다");
    }

    @Test
    void skipsServersThatCannotBeRead() throws Exception {
        FakeReader reader = new FakeReader();
        reader.unreadable.add("stopped");
        FleetService fleet = new FleetService(reader, clock);

        List<FleetService.Reading> got = readAll(fleet,
                List.of(server("up", ServerStatus.RUNNING), server("stopped", ServerStatus.STOPPED)), false);

        assertEquals(1, got.size());
        assertEquals("up", got.get(0).serverId());
        assertTrue(fleet.latest("stopped").isEmpty());
    }

    @Test
    void reusesFreshReadingsAndReadsAgainWhenForcedOrExpired() throws Exception {
        FakeReader reader = new FakeReader();
        FleetService fleet = new FleetService(reader, clock);
        List<Server> servers = List.of(server("a", ServerStatus.RUNNING));

        readAll(fleet, servers, false);
        assertEquals(0, fleet.read(servers, false, r -> { }), "5분 안에는 다시 읽지 않는다");
        assertTrue(fleet.fresh("a"));

        clock.now = clock.now.plus(FleetService.TTL).plusSeconds(1);
        assertFalse(fleet.fresh("a"));
        readAll(fleet, servers, false);
        assertEquals(2, reader.read.size(), "오래되면 다시 읽는다");

        readAll(fleet, servers, true);
        assertEquals(3, reader.read.size(), "새로고침은 값이 있어도 다시 읽는다");
    }

    @Test
    void aFailedServerIsNotRetriedAutomatically() throws Exception {
        FakeReader reader = new FakeReader();
        reader.failing.add("a");
        FleetService fleet = new FleetService(reader, clock);
        List<Server> servers = List.of(server("a", ServerStatus.RUNNING));

        FleetService.Reading first = readAll(fleet, servers, false).get(0);
        assertTrue(first.failed());
        assertEquals("접속 실패", first.problem());

        assertEquals(0, fleet.read(servers, false, r -> { }), "실패한 서버는 다시 접속하지 않는다");
        clock.now = clock.now.plus(FleetService.TTL).plusSeconds(1);
        assertEquals(0, fleet.read(servers, false, r -> { }), "시간이 지나도 새로고침하기 전에는 다시 접속하지 않는다");
        assertEquals(1, reader.read.size());

        reader.failing.clear();
        assertEquals(false, readAll(fleet, servers, true).get(0).failed(), "새로고침하면 다시 읽는다");
    }

    @Test
    void cancelSkipsServersThatHaveNotStarted() throws Exception {
        FakeReader reader = new FakeReader();
        reader.holdMillis = 60;
        FleetService fleet = new FleetService(reader, clock);
        List<Server> servers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            servers.add(server("s" + i, ServerStatus.RUNNING));
        }
        CountDownLatch firstTwo = new CountDownLatch(2);
        fleet.read(servers, false, r -> firstTwo.countDown());
        Thread.sleep(20);   // 첫 두 대가 읽는 중
        fleet.cancel();
        assertTrue(firstTwo.await(2, TimeUnit.SECONDS));
        Thread.sleep(150);

        assertEquals(2, reader.read.size(), "시작하지 않은 서버는 읽지 않는다");
    }

    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
