package com.infradesk.ui.metrics;

import com.infradesk.core.Metrics;
import com.infradesk.ui.Theme;

import java.awt.Color;
import java.util.List;
import java.util.function.DoubleFunction;
import java.util.function.Function;

/** 모니터링하는 세 가지 메트릭: 각각의 제목, 색, 형식, 그리고 {@link Metrics}에서 읽는 방법. */
public enum MetricKind {

    CPU("CPU", List.of(new MetricCard.Series("CPU", Theme.ACCENT)), Formats::percent, 100.0,
            m -> List.of(m.cpuPercent())),
    MEMORY("메모리", List.of(new MetricCard.Series("메모리", Theme.ACCENT)), Formats::percent, 100.0,
            m -> List.of(m.memoryPercent())),
    NETWORK("네트워크", List.of(new MetricCard.Series("수신", Theme.ACCENT), new MetricCard.Series("송신", new Color(0xD95926))),
            Formats::rate, null, m -> List.of(m.networkInBps(), m.networkOutBps()));

    private final String title;
    private final List<MetricCard.Series> series;
    private final DoubleFunction<String> format;
    private final Double yMax;
    private final Function<Metrics, List<List<Metrics.Sample>>> select;

    MetricKind(String title, List<MetricCard.Series> series, DoubleFunction<String> format, Double yMax,
               Function<Metrics, List<List<Metrics.Sample>>> select) {
        this.title = title;
        this.series = series;
        this.format = format;
        this.yMax = yMax;
        this.select = select;
    }

    public String title() {
        return title;
    }

    public List<MetricCard.Series> series() {
        return series;
    }

    DoubleFunction<String> format() {
        return format;
    }

    /** y 범위의 고정된 상단(백분율은 100). 데이터에 맞추려면 null. */
    Double yMax() {
        return yMax;
    }

    /** 시리즈마다 샘플 목록 하나. {@link #series()} 순서대로. */
    List<List<Metrics.Sample>> select(Metrics metrics) {
        return select.apply(metrics);
    }
}
