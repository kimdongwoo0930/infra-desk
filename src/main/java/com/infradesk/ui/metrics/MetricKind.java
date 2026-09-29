package com.infradesk.ui.metrics;

import com.infradesk.core.Metrics;
import com.infradesk.ui.Theme;

import java.awt.Color;
import java.util.List;
import java.util.function.DoubleFunction;
import java.util.function.Function;

/** The three monitored metrics: how each is titled, coloured, formatted and read out of {@link Metrics}. */
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

    /** Fixed top of the y range (100 for percentages), or null to fit the data. */
    Double yMax() {
        return yMax;
    }

    /** One sample list per series, in {@link #series()} order. */
    List<List<Metrics.Sample>> select(Metrics metrics) {
        return select.apply(metrics);
    }
}
