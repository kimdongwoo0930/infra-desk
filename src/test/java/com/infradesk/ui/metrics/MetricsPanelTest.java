package com.infradesk.ui.metrics;

import com.infradesk.core.Metrics;
import com.infradesk.core.Metrics.Sample;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractButton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 모니터링 탭: 구간 선택(실시간 · 1시간 · 6시간 · 24시간 · 7일)이 무엇을 알리는지와 지금 값. 가짜 값만 쓴다. */
class MetricsPanelTest {

    /** 구간 선택의 칸 번호. */
    private static final int LIVE = 0;
    private static final int H1 = 1;
    private static final int H6 = 2;
    private static final int H24 = 3;
    private static final int D7 = 4;

    private static final class Fixture {
        final MetricsPanel panel = new MetricsPanel();
        final List<String> events = new ArrayList<>();

        Fixture() {
            panel.onLiveToggle(on -> events.add("live:" + on));
            panel.onRangeChanged(() -> events.add("range:" + panel.selectedRange()));
        }

        void click(int segment) {
            ((AbstractButton) panel.rangeControl().getComponent(segment)).doClick();
        }
    }

    private static List<Sample> samples(double... values) {
        List<Sample> list = new ArrayList<>();
        Instant t = Instant.parse("2025-10-27T06:00:00Z");
        for (double v : values) {
            list.add(new Sample(t, v));
            t = t.plusSeconds(60);
        }
        return list;
    }

    @Test
    void 처음에는_1시간이_선택되어_있고_실시간이_아니다() {
        Fixture f = new Fixture();

        assertEquals(H1, f.panel.segmentIndex());
        assertEquals(Duration.ofHours(1), f.panel.selectedRange());
        assertFalse(f.panel.isLiveSelected());
    }

    @Test
    void 실시간_칸을_고르면_실시간_켜기를_알린다() {
        Fixture f = new Fixture();

        f.click(LIVE);

        assertEquals(List.of("live:true"), f.events);
        assertTrue(f.panel.isLiveSelected());
    }

    @Test
    void 기록_기간을_고르면_기간_변경을_알린다() {
        Fixture f = new Fixture();

        f.click(H24);

        assertEquals(List.of("range:PT24H"), f.events);
        assertEquals(Duration.ofHours(24), f.panel.selectedRange());
    }

    @Test
    void 실시간에서_기간으로_넘어가면_실시간_끄기만_알리고_기간_변경은_따로_알리지_않는다() {
        // MainFrame이 실시간을 끄면서 그 기간을 읽는다. 둘 다 알리면 같은 기간을 두 번 읽게 된다.
        Fixture f = new Fixture();
        f.click(LIVE);
        f.events.clear();

        f.click(H6);

        assertEquals(List.of("live:false"), f.events);
        assertFalse(f.panel.isLiveSelected());
        assertEquals(Duration.ofHours(6), f.panel.selectedRange(), "끄기를 알릴 때 이미 고른 기간이 정해져 있어야 읽을 수 있다");
    }

    @Test
    void 실시간을_보던_중에도_마지막으로_고른_기간을_기억한다() {
        Fixture f = new Fixture();
        f.click(D7);
        f.click(LIVE);

        assertEquals(Duration.ofDays(7), f.panel.selectedRange());

        f.panel.setLiveSelected(false);   // 실시간이 스스로 꺼졌다
        assertEquals(D7, f.panel.segmentIndex(), "7일로 돌아간다");
    }

    @Test
    void 코드가_선택을_바꿀_때는_알리지_않는다() {
        Fixture f = new Fixture();

        f.panel.setLiveSelected(true);
        f.panel.setLiveSelected(false);

        assertEquals(List.of(), f.events);
    }

    @Test
    void 실시간을_켜지_못했으면_되돌릴_수_있다() {
        // SSH 설정 창에서 취소하면 MainFrame이 setLiveSelected(false)로 되돌린다.
        Fixture f = new Fixture();
        f.click(H6);
        f.events.clear();
        f.click(LIVE);

        f.panel.setLiveSelected(false);

        assertEquals(H6, f.panel.segmentIndex());
        assertFalse(f.panel.isLiveSelected());
        assertEquals(List.of("live:true"), f.events, "되돌리는 것은 알리지 않는다");
    }

    @Test
    void 서버가_꺼져_있으면_실시간_칸을_누를_수_없다() {
        Fixture f = new Fixture();
        f.panel.setLiveEnabled(false, "서버가 실행 중일 때 쓸 수 있어요");

        f.click(LIVE);

        assertEquals(List.of(), f.events);
        assertEquals(H1, f.panel.segmentIndex());
    }

    @Test
    void 직접_연결_서버는_1시간_외의_기간을_누를_수_없다() {
        Fixture f = new Fixture();
        f.panel.setHistoryRangesAvailable(false);

        f.click(H6);
        f.click(H24);
        f.click(D7);

        assertEquals(List.of(), f.events);
        assertEquals(H1, f.panel.segmentIndex());
    }

    @Test
    void 기간을_쓸_수_없게_되면_보던_기간에서_1시간으로_돌아간다_알리지는_않는다() {
        Fixture f = new Fixture();
        f.click(H24);
        f.events.clear();

        f.panel.setHistoryRangesAvailable(false);

        assertEquals(H1, f.panel.segmentIndex());
        assertEquals(Duration.ofHours(1), f.panel.selectedRange());
        assertEquals(List.of(), f.events, "MainFrame이 서버를 바꾸면서 직접 읽는다");
    }

    @Test
    void 다시_쓸_수_있게_되면_다른_기간을_누를_수_있다() {
        Fixture f = new Fixture();
        f.panel.setHistoryRangesAvailable(false);
        f.panel.setHistoryRangesAvailable(true);

        f.click(H24);

        assertEquals(Duration.ofHours(24), f.panel.selectedRange());
    }

    @Test
    void 기록을_보이면_카드마다_지금_값이_보인다() {
        Fixture f = new Fixture();

        f.panel.showHistory(new Metrics(samples(10, 27), samples(40, 45), samples(1000, 30_000), samples(500, 9_000)));

        assertEquals("27%", f.panel.cpuCard().valueText());
        assertEquals("45%", f.panel.memoryCard().valueText());
        // 네트워크는 수신+송신의 합: 30,000 + 9,000 = 39,000 B/s
        assertEquals("39.0 KB/s", f.panel.networkCard().valueText());
        assertTrue(f.panel.cpuCard().showsChart());
    }

    @Test
    void 데이터가_없으면_안내_문구를_보인다() {
        Fixture f = new Fixture();

        f.panel.showHistory(Metrics.EMPTY);

        assertEquals("—", f.panel.cpuCard().valueText());
        assertFalse(f.panel.cpuCard().showsChart());
    }

    @Test
    void 기간에_맞는_OCI_간격이_안내_문구에_적힌다() {
        Fixture f = new Fixture();
        Metrics m = new Metrics(samples(1, 2), samples(1, 2), samples(1, 2), samples(1, 2));

        f.panel.showHistory(m);
        assertTrue(f.panel.statusText().contains("1분 간격"));

        f.click(H24);
        f.panel.showHistory(m);
        assertTrue(f.panel.statusText().contains("5분 간격"));

        f.click(D7);
        f.panel.showHistory(m);
        assertTrue(f.panel.statusText().contains("15분 간격"));
    }

    @Test
    void 실시간_칸을_골라도_기록_기간은_null이_아니다() {
        // selectedRange가 null이면 MainFrame의 읽기(service.metrics(…, range))가 깨진다.
        Fixture f = new Fixture();
        f.click(LIVE);

        assertEquals(Duration.ofHours(1), f.panel.selectedRange());
    }
}
