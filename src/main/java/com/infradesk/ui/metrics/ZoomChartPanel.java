package com.infradesk.ui.metrics;

import com.infradesk.core.Metrics;
import com.infradesk.ui.Theme;
import org.knowm.xchart.XChartPanel;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.XYSeries;
import org.knowm.xchart.style.Styler;
import org.knowm.xchart.style.XYStyler;
import org.knowm.xchart.style.markers.SeriesMarkers;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.function.Consumer;
import javax.swing.UIManager;

/**
 * Full-size chart with axes that zooms like a stock chart: the wheel zooms around the cursor, a drag
 * pans, a double-click resets. Zooming out past the full range asks the owner to widen the range.
 */
final class ZoomChartPanel extends XChartPanel<XYChart> {

    private static final double WHEEL_STEP = 1.25;
    private static final long WIDEN_COOLDOWN_MS = 700;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ZoneId.systemDefault());

    private final MetricKind kind;
    private List<List<Metrics.Sample>> data = List.of();
    private ZoomWindow window;
    private DateTimeFormatter tooltipFormat = TIME;
    private double yTop = 100;
    private boolean painted;
    private int mouseX = -1;
    private int lastDragX = -1;
    private long lastWidenAt;
    private Runnable onZoomOutFull = () -> { };
    private Consumer<Boolean> onZoomChanged = zoomed -> { };

    ZoomChartPanel(MetricKind kind) {
        super(createChart(kind));
        this.kind = kind;
        setOpaque(false);
        setBackground(Theme.PANEL_BG);
        for (var l : getMouseListeners()) {
            if (l.getClass().getName().contains("PopUpMenu")) {
                removeMouseListener(l);
            }
        }
        setComponentPopupMenu(null);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                lastDragX = e.getButton() == MouseEvent.BUTTON1 ? e.getX() : -1;
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                lastDragX = -1;
                setCursor(Cursor.getDefaultCursor());
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 2) {
                    resetZoom();
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (lastDragX >= 0 && window != null && painted && !window.isFull()) {
                    double plotWidth = plotRight() - plotLeft();
                    if (plotWidth > 0) {
                        window.pan(-(e.getX() - lastDragX) / plotWidth * window.span());
                        applyWindow();
                    }
                    lastDragX = e.getX();
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
                mouseX = e.getX();
                repaint();
            }

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

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                zoomAt(e.getX(), Math.pow(WHEEL_STEP, e.getPreciseWheelRotation()));
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    private static XYChart createChart(MetricKind kind) {
        XYChart chart = new XYChartBuilder().width(800).height(400).build();
        XYStyler st = chart.getStyler();
        Font font = UIManager.getFont("defaultFont");
        Font base = (font != null ? font : new Font(Font.SANS_SERIF, Font.PLAIN, 12)).deriveFont(Font.PLAIN, 11f);
        st.setChartBackgroundColor(Theme.PANEL_BG);
        st.setPlotBackgroundColor(Theme.PANEL_BG);
        st.setPlotBorderVisible(false);
        st.setPlotGridLinesVisible(true);
        st.setPlotGridLinesColor(Theme.DIVIDER);
        st.setPlotGridVerticalLinesVisible(false);
        st.setChartFontColor(Theme.TEXT_SECONDARY);
        st.setAxisTickLabelsColor(Theme.TEXT_MUTED);
        st.setAxisTickLabelsFont(base);
        st.setAxisTickMarksColor(Theme.DIVIDER);
        st.setAxisTitlesVisible(false);
        st.setChartTitleVisible(false);
        st.setChartPadding(10);
        st.setMarkerSize(0);
        st.setAntiAlias(true);
        st.setTimezone(TimeZone.getDefault());
        st.setXAxisMaxLabelCount(8);
        st.setYAxisMin(0.0);
        st.setYAxisTickLabelsFormattingFunction(kind.format()::apply);
        st.setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);
        st.setLegendVisible(kind.series().size() > 1);
        st.setLegendPosition(Styler.LegendPosition.InsideNW);
        st.setLegendBackgroundColor(Theme.PANEL_BG);
        st.setLegendBorderColor(Theme.DIVIDER);
        st.setLegendFont(base);
        return chart;
    }

    /** Called when the user zooms out past everything that is loaded (e.g. to switch to a longer range). */
    void onZoomOutFull(Runnable listener) {
        this.onZoomOutFull = listener;
    }

    /** Called with true when the view is zoomed in, false when it shows everything. */
    void onZoomChanged(Consumer<Boolean> listener) {
        this.onZoomChanged = listener;
    }

    boolean isZoomed() {
        return window != null && !window.isFull();
    }

    void resetZoom() {
        if (window != null) {
            boolean wasZoomed = isZoomed();
            window.reset();
            applyWindow();
            if (wasZoomed) {
                onZoomChanged.accept(false);
            }
        }
    }

    /** Replaces the data. A zoomed view stays put, a full view follows the new data. */
    void setData(List<List<Metrics.Sample>> newData) {
        data = newData;
        XYChart chart = getChart();
        for (MetricCard.Series meta : kind.series()) {
            chart.removeSeries(meta.name());
        }
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        double step = Double.MAX_VALUE;
        for (int i = 0; i < data.size(); i++) {
            List<Metrics.Sample> d = data.get(i);
            if (d.isEmpty()) {
                continue;
            }
            List<Date> xs = new ArrayList<>(d.size());
            List<Double> ys = new ArrayList<>(d.size());
            for (Metrics.Sample s : d) {
                xs.add(Date.from(s.time()));
                ys.add(s.value());
            }
            MetricCard.Series meta = kind.series().get(i);
            XYSeries xy = chart.addSeries(meta.name(), xs, ys);
            xy.setLineColor(meta.color());
            xy.setLineStyle(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            xy.setMarker(SeriesMarkers.NONE);
            long first = d.getFirst().time().toEpochMilli();
            long last = d.getLast().time().toEpochMilli();
            min = Math.min(min, first);
            max = Math.max(max, last);
            if (d.size() > 1) {
                step = Math.min(step, (double) (last - first) / (d.size() - 1));
            }
        }
        if (min > max) {
            return;
        }
        if (max - min < 1000) {
            min -= 30_000;
            max += 30_000;
        }
        double minSpan = step == Double.MAX_VALUE ? 60_000 : Math.max(4 * step, 5_000);
        tooltipFormat = max - min >= 20 * 3_600_000.0 ? DAY_TIME : TIME;
        boolean wasZoomed = isZoomed();
        if (window == null) {
            window = new ZoomWindow(min, max, minSpan);
        } else {
            window.setFull(min, max, minSpan);
        }
        applyWindow();
        if (wasZoomed != isZoomed()) {
            onZoomChanged.accept(isZoomed());
        }
    }

    private void zoomAt(int x, double factor) {
        if (window == null || !painted) {
            return;
        }
        boolean wasZoomed = isZoomed();
        double anchor = getChart().getChartXFromCoordinate(x);
        if (!window.zoom(factor, anchor)) {
            long now = System.currentTimeMillis();
            if (now - lastWidenAt > WIDEN_COOLDOWN_MS) {
                lastWidenAt = now;
                onZoomOutFull.run();
            }
            return;
        }
        applyWindow();
        if (wasZoomed != isZoomed()) {
            onZoomChanged.accept(isZoomed());
        }
    }

    /** Points the axes at the view and fits the y range to what is visible. */
    private void applyWindow() {
        XYStyler st = getChart().getStyler();
        st.setXAxisMin(window.min());
        st.setXAxisMax(window.max());
        Double fixed = kind.yMax();
        if (fixed != null) {
            yTop = fixed;
        } else {
            double top = 0;
            for (List<Metrics.Sample> d : data) {
                for (Metrics.Sample s : d) {
                    double t = s.time().toEpochMilli();
                    if (t >= window.min() && t <= window.max()) {
                        top = Math.max(top, s.value());
                    }
                }
            }
            yTop = top <= 0 ? 1 : top * 1.15;
        }
        st.setYAxisMax(yTop);
        repaint();
    }

    private double plotLeft() {
        return getChart().getScreenXFromChart(window.min());
    }

    private double plotRight() {
        return getChart().getScreenXFromChart(window.max());
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        painted = true;
        if (mouseX < 0 || window == null || data.isEmpty()) {
            return;
        }
        if (mouseX < plotLeft() || mouseX > plotRight()) {
            return;
        }
        long target = Math.round(getChart().getChartXFromCoordinate(mouseX));
        Instant at = null;
        List<String> lines = new ArrayList<>();
        List<Color> colors = new ArrayList<>();
        for (int i = 0; i < data.size() && i < kind.series().size(); i++) {
            Metrics.Sample nearest = nearest(data.get(i), target);
            if (nearest == null) {
                continue;
            }
            at = at == null ? nearest.time() : at;
            lines.add((kind.series().size() > 1 ? kind.series().get(i).name() + " " : "") + kind.format().apply(nearest.value()));
            colors.add(kind.series().get(i).color());
        }
        if (at == null) {
            return;
        }
        int lineX = (int) Math.round(getChart().getScreenXFromChart(at.toEpochMilli()));
        int top = (int) Math.round(getChart().getScreenYFromChart(yTop));
        int bottom = (int) Math.round(getChart().getScreenYFromChart(0));
        ChartTooltip.draw(g, getFont(), getWidth(), lineX, top, bottom, top + 4, tooltipFormat.format(at), lines, colors);
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
