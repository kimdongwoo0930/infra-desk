package com.infradesk.ui.metrics;

import com.infradesk.core.Metrics;
import org.knowm.xchart.XChartPanel;
import org.knowm.xchart.XYChart;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleFunction;

/**
 * 자체 호버가 있는 XChart 패널: 가장 가까운 샘플에 세로선을 긋고, 그 시각과 각 시리즈의 값을
 * 적은 툴팁 하나를 보여준다. XChart의 기본 커서는 마우스 근처의 모든 점을 나열하고("18%, 18%, 18%")
 * 시리즈 이름이 없어서 대체했다.
 */
final class HoverChartPanel extends XChartPanel<XYChart> {

    /** 차트 스타일러가 쓰는 플롯 안쪽 여백(플롯 마진). 축은 숨겨서 플롯이 패널을 가득 채운다. */
    private static final int INSET = 2;

    private final List<MetricCard.Series> series;
    private final DoubleFunction<String> format;
    private List<List<Metrics.Sample>> data = List.of();
    private DateTimeFormatter timeFormat;
    private int mouseX = -1;

    HoverChartPanel(XYChart chart, List<MetricCard.Series> series, DoubleFunction<String> format) {
        super(chart);
        this.series = series;
        this.format = format;
        MouseAdapter hover = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                mouseX = e.getX();
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                mouseX = -1;
                repaint();
            }
        };
        addMouseMotionListener(hover);
        addMouseListener(hover);
    }

    void setData(List<List<Metrics.Sample>> data, DateTimeFormatter timeFormat) {
        this.data = data;
        this.timeFormat = timeFormat;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (mouseX < 0 || data.isEmpty()) {
            return;
        }
        List<Metrics.Sample> reference = data.stream().filter(d -> !d.isEmpty()).findFirst().orElse(List.of());
        if (reference.size() < 2) {
            return;
        }
        long t0 = reference.getFirst().time().toEpochMilli();
        long t1 = reference.getLast().time().toEpochMilli();
        double plotWidth = getWidth() - 2.0 * INSET;
        double fraction = Math.max(0, Math.min(1, (mouseX - INSET) / plotWidth));
        long target = t0 + Math.round(fraction * (t1 - t0));

        Instant at = null;
        List<String> lines = new ArrayList<>();
        List<Color> colors = new ArrayList<>();
        for (int i = 0; i < series.size() && i < data.size(); i++) {
            Metrics.Sample nearest = nearest(data.get(i), target);
            if (nearest == null) {
                continue;
            }
            at = at == null ? nearest.time() : at;
            lines.add((series.size() > 1 ? series.get(i).name() + " " : "") + format.apply(nearest.value()));
            colors.add(series.get(i).color());
        }
        if (at == null) {
            return;
        }
        int lineX = INSET + (int) Math.round((at.toEpochMilli() - t0) / (double) Math.max(1, t1 - t0) * plotWidth);

        ChartTooltip.draw(g, getFont(), getWidth(), lineX, 0, getHeight(), 0, timeFormat.format(at), lines, colors);
    }

    private static Metrics.Sample nearest(List<Metrics.Sample> samples, long target) {
        Metrics.Sample best = null;
        long bestDistance = Long.MAX_VALUE;
        for (Metrics.Sample s : samples) {
            long d = Math.abs(s.time().toEpochMilli() - target);
            if (d < bestDistance) {
                bestDistance = d;
                best = s;
            }
        }
        return best;
    }
}
