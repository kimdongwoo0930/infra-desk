package com.infradesk.ui.metrics;

import com.infradesk.alert.AlertSettings;
import com.infradesk.core.Metrics;
import com.infradesk.core.Metrics.Sample;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsSidePanelTest {

    private static final long GIB = 1024L * 1024 * 1024;
    private static final Instant T0 = Instant.parse("2025-10-27T06:00:00Z");

    private static List<Sample> series(double... values) {
        List<Sample> list = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            list.add(new Sample(T0.plusSeconds(i * 60L), values[i]));
        }
        return list;
    }

    // ── 디스크 ──────────────────────────────────────────────────

    @Test
    void 디스크는_퍼센트와_사용량_막대를_보인다() {
        MetricsSidePanel side = new MetricsSidePanel();

        side.setDisk(42 * GIB, 99 * GIB, T0);

        assertEquals("42%", side.diskPercentText());
        assertEquals("42 / 99 GB", side.diskSizeText());
        assertEquals(100.0 * 42 / 99, side.diskBarPercent(), 1e-9);
    }

    @Test
    void 디스크_안내는_읽은_시각만_적고_몇_분마다라고_적지_않는다() {
        // 서버 정보는 서버를 고를 때(5분 캐시) 읽는다. 주기적으로 읽는 것처럼 보이면 사실이 아니다.
        MetricsSidePanel side = new MetricsSidePanel();

        side.setDisk(42 * GIB, 99 * GIB, T0);

        assertTrue(side.diskNoteText().startsWith("SSH로 읽은 값 · "));
        assertTrue(side.diskNoteText().endsWith(" 기준"));
        assertTrue(!side.diskNoteText().contains("마다"));
    }

    @Test
    void 작은_용량은_소수_한_자리로_보인다() {
        MetricsSidePanel side = new MetricsSidePanel();

        side.setDisk(3 * GIB / 2, 8 * GIB, T0);   // 1.5 / 8 GB

        assertEquals("1.5 / 8.0 GB", side.diskSizeText());
    }

    @Test
    void 디스크_크기를_모르면_줄표다() {
        MetricsSidePanel side = new MetricsSidePanel();
        side.setDisk(42 * GIB, 99 * GIB, T0);

        side.setDisk(-1, -1, T0);

        assertEquals("—", side.diskPercentText());
        assertEquals(0.0, side.diskBarPercent());
    }

    @Test
    void 읽지_못했을_때의_안내를_보인다() {
        MetricsSidePanel side = new MetricsSidePanel();

        side.setDiskMessage("SSH 설정 후 표시");

        assertEquals("—", side.diskPercentText());
        assertEquals("SSH 설정 후 표시", side.diskNoteText());
    }

    // ── 알림 기준 ───────────────────────────────────────────────

    @Test
    void CPU_알림이_켜져_있으면_기준과_시간을_적는다() {
        AlertSettings on = new AlertSettings(true, true, true, true, 90, 5);

        assertEquals("CPU 90% 이상이 5분 이상 계속되면 디스코드로 알려요.", MetricsSidePanel.alertText(on));
        assertEquals(90.0, MetricsSidePanel.cpuThreshold(on));
    }

    @Test
    void 전체_알림이_꺼져_있으면_기준선도_없다() {
        AlertSettings off = new AlertSettings(false, true, true, true, 90, 5);

        assertNull(MetricsSidePanel.cpuThreshold(off));
        assertTrue(MetricsSidePanel.alertText(off).contains("꺼져 있어요"));
    }

    @Test
    void CPU_종류만_꺼져_있어도_기준선이_없다() {
        // 전체는 켜져 있어도 "CPU가 높을 때" 알림을 껐으면 알림이 나가지 않으므로 기준선을 그리면 거짓이다.
        AlertSettings cpuOff = new AlertSettings(true, true, true, false, 90, 5);

        assertNull(MetricsSidePanel.cpuThreshold(cpuOff));
    }

    @Test
    void 알림이_켜져_있으면_바꾸기_꺼져_있으면_켜기_버튼이다() {
        MetricsSidePanel side = new MetricsSidePanel();

        side.setAlert(new AlertSettings(true, true, true, true, 80, 3));
        assertEquals("설정에서 바꾸기", side.alertButtonText());
        assertEquals("CPU 80% 이상이 3분 이상 계속되면 디스코드로 알려요.", side.alertBodyText());

        side.setAlert(new AlertSettings(false, true, true, true, 80, 3));
        assertEquals("설정에서 켜기", side.alertButtonText());
    }

    @Test
    void 설정_버튼을_누르면_설정_열기를_알린다() {
        MetricsSidePanel side = new MetricsSidePanel();
        AtomicInteger opened = new AtomicInteger();
        side.onOpenSettings(opened::incrementAndGet);

        side.alertButton().doClick();

        assertEquals(1, opened.get());
    }

    @Test
    void 카드는_내용보다_길게_늘어나지_않는다() {
        // 구조: 요약 카드, 간격, 디스크 카드, 간격, 알림 카드, 남는 공간. 알림 카드가 남는 높이를 먹으면 아래로 길게 늘어난다.
        MetricsSidePanel side = new MetricsSidePanel();
        side.setAlert(new AlertSettings(true, true, true, true, 90, 5));

        for (int index : new int[] {0, 2, 4}) {
            java.awt.Component card = side.getComponent(index);
            assertEquals(card.getPreferredSize().height, card.getMaximumSize().height, index + "번째 카드");
        }
    }

    // ── 요약이 MetricsPanel의 데이터에서 나온다 ─────────────────

    @Test
    void 기록을_보이면_요약_세_줄이_채워진다() {
        MetricsPanel panel = new MetricsPanel();

        panel.showHistory(new Metrics(series(10, 40, 20), series(40, 45, 50), series(1000, 30_000, 500), series(100, 9_000, 100)));

        MetricsSidePanel side = panel.side();
        assertTrue(side.cpuText().startsWith("평균 23% · 최대 40%"), side.cpuText());
        assertTrue(side.memoryText().startsWith("평균 45% · 최대 50%"), side.memoryText());
        assertTrue(side.networkText().startsWith("최대 39.0 KB/s"), side.networkText());
    }

    @Test
    void 불러오는_중이거나_메시지가_있으면_요약이_줄표로_돌아간다() {
        MetricsPanel panel = new MetricsPanel();
        panel.showHistory(new Metrics(series(10, 40), series(40, 45), series(1000, 2000), series(100, 200)));

        panel.showLoading();

        assertEquals("—", panel.side().cpuText());
        assertEquals("—", panel.side().networkText());

        panel.showHistory(new Metrics(series(10, 40), series(40, 45), series(1000, 2000), series(100, 200)));
        panel.showMessage("서버가 실행 중일 때 표시돼요");
        assertEquals("—", panel.side().memoryText());
    }
}
