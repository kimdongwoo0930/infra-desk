package com.infradesk.ui.metrics;

import com.infradesk.core.Metrics.Sample;
import com.infradesk.ui.metrics.MetricSummary.Stat;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricSummaryTest {

    private static final Instant T0 = Instant.parse("2025-10-27T06:00:00Z");

    /** T0부터 {@code stepSeconds} 간격으로 값을 놓는다. */
    private static List<Sample> series(long stepSeconds, double... values) {
        List<Sample> list = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            list.add(new Sample(T0.plusSeconds(i * stepSeconds), values[i]));
        }
        return list;
    }

    private static String clock(Instant t, String pattern) {
        return DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault()).format(t);
    }

    @Test
    void 평균과_최대와_최대였던_시각을_구한다() {
        Stat s = MetricSummary.stat(series(60, 10, 40, 20, 30)).orElseThrow();

        assertEquals(25.0, s.average(), 1e-9);
        assertEquals(40.0, s.max(), 1e-9);
        assertEquals(T0.plusSeconds(60), s.maxAt());
    }

    @Test
    void 최대가_여러_번이면_가장_최근_시각을_쓴다() {
        Stat s = MetricSummary.stat(series(60, 50, 10, 50, 20)).orElseThrow();

        assertEquals(T0.plusSeconds(120), s.maxAt());
    }

    @Test
    void 점이_없으면_통계도_없다() {
        assertEquals(Optional.empty(), MetricSummary.stat(List.of()));
    }

    @Test
    void 수신과_송신은_같은_시각끼리_더한다() {
        List<Sample> total = MetricSummary.sumByTime(series(60, 100, 200), series(60, 10, 20));

        assertEquals(2, total.size());
        assertEquals(110.0, total.get(0).value(), 1e-9);
        assertEquals(220.0, total.get(1).value(), 1e-9);
    }

    @Test
    void 한쪽에만_있는_시각은_그_값만_쓰고_시각_순서로_돌려준다() {
        List<Sample> a = List.of(new Sample(T0.plusSeconds(120), 5), new Sample(T0, 1));
        List<Sample> b = List.of(new Sample(T0.plusSeconds(60), 7));

        List<Sample> total = MetricSummary.sumByTime(a, b);

        assertEquals(List.of(1.0, 7.0, 5.0), total.stream().map(Sample::value).toList());
        assertTrue(total.get(0).time().isBefore(total.get(1).time()));
    }

    @Test
    void 사용률_한_줄은_평균과_최대와_시각이다() {
        String line = MetricSummary.percentLine(series(60, 10, 40, 20, 30), false);

        assertEquals("평균 25% · 최대 40% (" + clock(T0.plusSeconds(60), "HH:mm") + ")", line);
    }

    @Test
    void 네트워크_한_줄은_수신_송신_합의_최대다() {
        // 수신 30,000 + 송신 9,000 = 39,000 B/s 가 최대
        String line = MetricSummary.rateLine(series(60, 1000, 30_000), series(60, 500, 9_000), false);

        assertEquals("최대 39.0 KB/s (" + clock(T0.plusSeconds(60), "HH:mm") + ")", line);
    }

    @Test
    void 데이터가_없으면_줄표다() {
        assertEquals("—", MetricSummary.percentLine(List.of(), false));
        assertEquals("—", MetricSummary.rateLine(List.of(), List.of(), false));
    }

    @Test
    void 시각_형식은_실시간은_초까지_긴_기간은_날짜까지다() {
        List<Sample> hour = series(60, 1, 2, 3);
        assertEquals(clock(T0, "HH:mm:ss"), MetricSummary.timeFormat(hour, true).format(T0));
        assertEquals(clock(T0, "HH:mm"), MetricSummary.timeFormat(hour, false).format(T0));

        // 24시간(5분 간격 289점)처럼 20시간 이상을 보여 주면 날짜가 붙는다
        List<Sample> day = new ArrayList<>();
        for (int i = 0; i <= 288; i++) {
            day.add(new Sample(T0.plusSeconds(i * 300L), i));
        }
        assertEquals(clock(T0, "M/d HH:mm"), MetricSummary.timeFormat(day, false).format(T0));
    }
}
