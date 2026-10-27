package com.infradesk.ui.metrics;

import com.infradesk.ssh.ProcStats;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 실시간이 스스로 꺼지기까지 남은 시간과, 그것을 알리는 안내 문구. */
class LiveRemainingTest {

    private static final class Fixture {
        final AtomicLong nanos = new AtomicLong();
        final LiveController live = new LiveController(new LiveController.Hooks() {
            @Override
            public void open() {
            }

            @Override
            public void close() {
            }

            @Override
            public void showToggle(boolean on) {
            }

            @Override
            public void showStatus(String text, boolean error) {
            }
        }, new LiveController.Timeout() {
            @Override
            public void start(Duration delay, Runnable onExpire) {
            }

            @Override
            public void cancel() {
            }
        }, nanos::get);

        void advance(Duration d) {
            nanos.addAndGet(d.toNanos());
        }
    }

    @Test
    void 켜기_전이나_연결하는_중에는_남은_시간이_없다() {
        Fixture f = new Fixture();
        assertEquals(Optional.empty(), f.live.remaining());

        f.live.userToggle(true);   // CONNECTING
        assertEquals(Optional.empty(), f.live.remaining());
    }

    @Test
    void 연결되면_열_분에서_시작해_시간이_흐르는_만큼_줄어든다() {
        Fixture f = new Fixture();
        f.live.userToggle(true);
        f.live.opened();

        assertEquals(Duration.ofMinutes(10), f.live.remaining().orElseThrow());

        f.advance(Duration.ofMinutes(3).plusSeconds(30));
        assertEquals(Duration.ofMinutes(6).plusSeconds(30), f.live.remaining().orElseThrow());
    }

    @Test
    void 열_분이_지나도_음수가_되지_않는다() {
        Fixture f = new Fixture();
        f.live.userToggle(true);
        f.live.opened();

        f.advance(Duration.ofMinutes(11));

        assertEquals(Duration.ZERO, f.live.remaining().orElseThrow());
    }

    @Test
    void 일시정지하면_없고_다시_연결하면_열_분이_새로_시작한다() {
        Fixture f = new Fixture();
        f.live.userToggle(true);
        f.live.opened();
        f.advance(Duration.ofMinutes(8));

        f.live.pause();
        assertEquals(Optional.empty(), f.live.remaining());

        f.live.resume();
        f.live.opened();
        assertEquals(Duration.ofMinutes(10), f.live.remaining().orElseThrow(), "화면이 다시 보이면 10분이 새로 시작한다");
    }

    @Test
    void 끄면_남은_시간이_없다() {
        Fixture f = new Fixture();
        f.live.userToggle(true);
        f.live.opened();

        f.live.userToggle(false);

        assertEquals(Optional.empty(), f.live.remaining());
    }

    // ── 안내 문구 ───────────────────────────────────────────────

    @Test
    void 남은_분을_올림해서_적는다() {
        assertEquals("SSH로 /proc을 2초마다 읽어요 · 8분 뒤 자동으로 꺼져요",
                MetricsPanel.liveNote(2, Optional.of(Duration.ofMinutes(8))));
        assertEquals("SSH로 /proc을 2초마다 읽어요 · 10분 뒤 자동으로 꺼져요",
                MetricsPanel.liveNote(2, Optional.of(Duration.ofMinutes(9).plusSeconds(30))));
        assertEquals("SSH로 /proc을 2초마다 읽어요 · 2분 뒤 자동으로 꺼져요",
                MetricsPanel.liveNote(2, Optional.of(Duration.ofSeconds(61))));
    }

    @Test
    void 일_분도_안_남았으면_일_분_안에라고_적는다() {
        assertEquals("SSH로 /proc을 2초마다 읽어요 · 1분 안에 자동으로 꺼져요",
                MetricsPanel.liveNote(2, Optional.of(Duration.ofSeconds(45))));
        assertEquals("SSH로 /proc을 2초마다 읽어요 · 1분 안에 자동으로 꺼져요",
                MetricsPanel.liveNote(2, Optional.of(Duration.ZERO)));
    }

    @Test
    void 남은_시간을_모르면_읽는_주기만_적는다() {
        assertEquals("SSH로 /proc을 2초마다 읽어요", MetricsPanel.liveNote(2, Optional.empty()));
    }

    @Test
    void 실시간_샘플을_받으면_안내_문구에_남은_시간이_붙는다() {
        MetricsPanel panel = new MetricsPanel();
        panel.setLiveRemaining(() -> Optional.of(Duration.ofMinutes(8)));

        panel.addLive(new ProcStats.Sample(Instant.parse("2026-01-01T12:00:00Z"), 20, 40, 1000, 500));

        assertTrue(panel.statusText().endsWith("8분 뒤 자동으로 꺼져요"), panel.statusText());
    }
}
