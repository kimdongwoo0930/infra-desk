package com.infradesk.ui.metrics;

import com.infradesk.core.Metrics;
import com.infradesk.ui.Theme;
import org.knowm.xchart.XChartPanel;
import org.knowm.xchart.XYChart;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleFunction;

/**
 * XChart panel with our own hover: a vertical line at the nearest sample and one tooltip naming
 * the time and each series' value at that time. XChart's built-in cursor lists every point near
 * the mouse ("18%, 18%, 18%") and has no series names, so it's replaced.
 */
final class HoverChartPanel extends XChartPanel<XYChart> {

    /** Plot inset used by the chart styler (plot margin); axes are hidden so the plot fills the panel. */
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

        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(Theme.TEXT_MUTED);
            g2.setStroke(new BasicStroke(1f));
            g2.drawLine(lineX, 0, lineX, getHeight());

            g2.setFont(getFont().deriveFont(11f));
            FontMetrics fm = g2.getFontMetrics();
            String header = timeFormat.format(at);
            int swatch = 10;
            int width = fm.stringWidth(header);
            for (String l : lines) {
                width = Math.max(width, swatch + 5 + fm.stringWidth(l));
            }
            int pad = 6;
            int lineH = fm.getHeight();
            int boxW = width + pad * 2;
            int boxH = lineH * (lines.size() + 1) + pad;
            int boxX = lineX + 8 + boxW <= getWidth() ? lineX + 8 : lineX - 8 - boxW;
            boxX = Math.max(0, Math.min(boxX, getWidth() - boxW));
            int boxY = 0;

            g2.setColor(Theme.APP_BG);
            g2.fillRoundRect(boxX, boxY, boxW, boxH, 8, 8);
            g2.setColor(Theme.INPUT_BORDER);
            g2.drawRoundRect(boxX, boxY, boxW - 1, boxH - 1, 8, 8);

            int y = boxY + pad / 2 + fm.getAscent();
            g2.setColor(Theme.TEXT_MUTED);
            g2.drawString(header, boxX + pad, y);
            for (int i = 0; i < lines.size(); i++) {
                y += lineH;
                g2.setColor(colors.get(i));
                g2.fillRoundRect(boxX + pad, y - fm.getAscent() / 2 - 1, swatch, 3, 3, 3);
                g2.setColor(Theme.TEXT);
                g2.drawString(lines.get(i), boxX + pad + swatch + 5, y);
            }
        } finally {
            g2.dispose();
        }
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
