package com.infradesk.service;

import com.infradesk.ssh.SshException.Kind;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SamplingGateTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-20T10:00:00Z"));
    private final SamplingGate gate = new SamplingGate(clock);

    private void advance(Duration d) {
        clock.now = clock.now.plus(d);
    }

    @Test
    void neverConnectsTwiceWithinTheMinimumGap() {
        assertTrue(gate.tryAcquire("s"));
        advance(Duration.ofSeconds(10));
        assertFalse(gate.tryAcquire("s"), "15초 안에는 다시 접속하지 않는다");
        advance(Duration.ofSeconds(6));
        assertTrue(gate.tryAcquire("s"));
    }

    @Test
    void twoCallersAskingAtTheSameTimeOnlyOneGetsThrough() {
        assertTrue(gate.tryAcquire("s"));
        assertFalse(gate.tryAcquire("s"));
    }

    @Test
    void serversAreIndependent() {
        assertTrue(gate.tryAcquire("a"));
        assertTrue(gate.tryAcquire("b"));
    }

    @Test
    void connectsOncePerMinuteWhenTheTimerAsks() {
        assertTrue(gate.tryAcquire("s"));
        advance(Duration.ofMinutes(1));
        assertTrue(gate.tryAcquire("s"));
    }

    @Test
    void transientFailuresBackOffTwoFiveThenFifteenMinutes() {
        assertTrue(gate.tryAcquire("s"));
        assertFalse(gate.failure("s", Kind.CONNECT), "일시적 실패는 멈추지 않는다");

        advance(Duration.ofMinutes(1));
        assertFalse(gate.tryAcquire("s"), "첫 실패 뒤 2분은 기다린다");
        advance(Duration.ofMinutes(1));
        assertTrue(gate.tryAcquire("s"));
        gate.failure("s", null);

        advance(Duration.ofMinutes(4));
        assertFalse(gate.tryAcquire("s"), "두 번째 실패 뒤 5분");
        advance(Duration.ofMinutes(1));
        assertTrue(gate.tryAcquire("s"));
        gate.failure("s", Kind.CHANNEL);

        advance(Duration.ofMinutes(14));
        assertFalse(gate.tryAcquire("s"), "세 번째부터는 15분");
        advance(Duration.ofMinutes(1));
        assertTrue(gate.tryAcquire("s"));
        gate.failure("s", Kind.CONNECT);

        advance(Duration.ofMinutes(14));
        assertFalse(gate.tryAcquire("s"), "그 뒤로도 15분을 넘기지 않는다");
        advance(Duration.ofMinutes(1));
        assertTrue(gate.tryAcquire("s"));
        assertTrue(gate.blocked("s").isEmpty(), "연결 문제는 멈추지 않고 계속 간격만 늘린다");
    }

    @Test
    void successClearsTheBackoff() {
        gate.tryAcquire("s");
        gate.failure("s", Kind.CONNECT);
        advance(Duration.ofMinutes(2));
        assertTrue(gate.tryAcquire("s"));
        gate.success("s");

        advance(Duration.ofMinutes(1));
        assertTrue(gate.tryAcquire("s"), "성공했으니 바로 다음 주기에 다시 접속한다");
        gate.failure("s", Kind.CONNECT);
        advance(Duration.ofMinutes(1));
        assertFalse(gate.tryAcquire("s"), "실패 횟수도 처음부터 센다(2분)");
    }

    @Test
    void authenticationStopsAfterTheSecondConsecutiveFailure() {
        assertTrue(gate.tryAcquire("s"));
        assertFalse(gate.failure("s", Kind.AUTH), "첫 인증 실패로는 멈추지 않는다");
        assertTrue(gate.blocked("s").isEmpty());

        advance(Duration.ofMinutes(3));
        assertTrue(gate.tryAcquire("s"));
        assertTrue(gate.failure("s", Kind.AUTH), "두 번째에 멈추고, 멈춘 것을 한 번 알린다");
        assertEquals(Kind.AUTH, gate.blocked("s").orElseThrow());

        advance(Duration.ofHours(6));
        assertFalse(gate.tryAcquire("s"), "몇 시간이 지나도 사용자가 고치기 전에는 접속하지 않는다");
        assertFalse(gate.failure("s", Kind.AUTH), "이미 멈췄으니 또 알리지 않는다");
    }

    @Test
    void hostKeyProblemsStopImmediately() {
        for (Kind kind : new Kind[] {Kind.HOST_KEY_CHANGED, Kind.HOST_KEY_REJECTED, Kind.KEY_FORMAT}) {
            SamplingGate g = new SamplingGate(clock);
            assertTrue(g.tryAcquire("s"));
            assertTrue(g.failure("s", kind), kind + "은 첫 실패에 멈춘다");
            advance(Duration.ofHours(1));
            assertFalse(g.tryAcquire("s"), kind + ": 다시 시도해도 성공할 수 없다");
        }
    }

    @Test
    void anAuthFailureFollowedBySuccessDoesNotCountTowardStopping() {
        gate.tryAcquire("s");
        gate.failure("s", Kind.AUTH);
        advance(Duration.ofMinutes(3));
        gate.tryAcquire("s");
        gate.success("s");

        advance(Duration.ofMinutes(1));
        gate.tryAcquire("s");
        assertFalse(gate.failure("s", Kind.AUTH), "연속이 아니므로 다시 처음부터");
        assertTrue(gate.blocked("s").isEmpty());
    }

    @Test
    void resetLetsAStoppedServerTryAgainButKeepsTheMinimumGap() {
        gate.tryAcquire("s");
        gate.failure("s", Kind.HOST_KEY_REJECTED);
        assertTrue(gate.blocked("s").isPresent());

        advance(Duration.ofSeconds(5));
        gate.reset("s");
        assertTrue(gate.blocked("s").isEmpty());
        assertFalse(gate.tryAcquire("s"), "새로고침을 연타해도 15초 간격은 지킨다");
        advance(Duration.ofSeconds(11));
        assertTrue(gate.tryAcquire("s"));
    }

    @Test
    void resetAllClearsEveryServer() {
        gate.tryAcquire("a");
        gate.failure("a", Kind.KEY_FORMAT);
        gate.tryAcquire("b");
        gate.failure("b", Kind.HOST_KEY_CHANGED);

        gate.resetAll();

        assertTrue(gate.blocked("a").isEmpty());
        assertTrue(gate.blocked("b").isEmpty());
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
