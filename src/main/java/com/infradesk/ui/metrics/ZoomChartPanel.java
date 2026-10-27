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
 * 축이 있는 전체 크기 차트. 주식 차트처럼 확대한다: 휠은 커서 주위로 확대하고, 드래그는
 * 이동하며, 더블클릭은 초기화한다. 전체 범위보다 더 축소하면 소유자에게 기간을 넓혀 달라고 요청한다.
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
    /** 선 아래를 옅은 색으로 채울지(디자인 v2 큰 차트). 확대 창은 채우지 않는다. */
    private boolean filled;
    private int mouseX = -1;
    private int lastDragX = -1;
    private long lastWidenAt;
    private Runnable onZoomOutFull = () -> { };
    private Consumer<Boolean> onZoomChanged = zoomed -> { };
    /** 세 차트가 같은 시점을 가리키게 하는 공유 상태. 없으면 이 차트만 자기 마우스 위치를 따른다. */
    private HoverSync sync;
    /** 알림 기준선(%). 없으면 그리지 않는다(CPU 차트만 쓴다). */
    private Double alertThreshold;

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
                shareHover(e.getX());
                repaint();
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                mouseX = e.getX();
                shareHover(e.getX());
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                mouseX = -1;
                if (sync != null) {
                    sync.clear();
                }
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
        // 세로축 눈금 사이를 띄운다. 기본은 네트워크가 11개로 너무 촘촘하다. 70이면 5개(0/50/…/200)가 된다.
        // 주의: 값을 키운다고 눈금이 줄지 않는다(100으로 올렸더니 오히려 9개로 늘었다). XChart가 "보기 좋은 간격"을
        // 고르는 방식 때문이라 값을 바꿀 때는 스냅샷으로 확인해야 한다. 백분율 차트는 70에서도 0/20/…/100%의 6개다.
        st.setYAxisTickMarkSpacingHint(70);
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

    /**
     * 세 차트가 같은 시점을 함께 가리키게 묶는다. 묶으면 이 차트의 마우스 위치가 모든 차트에 시각으로 전해지고,
     * 다른 차트의 마우스 위치도 이 차트에 그려진다.
     */
    void setSync(HoverSync sync) {
        this.sync = sync;
        sync.register(this);
    }

    /** 알림 기준선을 그린다(예: CPU 90%). null이면 지운다. y 범위가 고정된 차트(백분율)에서만 의미가 있다. */
    void setAlertThreshold(Double percent) {
        this.alertThreshold = percent;
        repaint();
    }

    /** 선 아래를 옅게 채운다(시안의 큰 차트). 다음 {@link #setData}부터 적용된다. */
    void setFilled(boolean filled) {
        this.filled = filled;
    }

    /** 차트 안의 범례를 보이거나 숨긴다. 카드 머리에 범례를 따로 두면 숨긴다. */
    void setLegendVisible(boolean visible) {
        getChart().getStyler().setLegendVisible(visible);
    }

    /** 불러온 전체보다 더 축소하면 호출된다(예: 더 긴 기간으로 바꾸기 위해). */
    void onZoomOutFull(Runnable listener) {
        this.onZoomOutFull = listener;
    }

    /** 확대된 상태이면 true, 전체를 보여주면 false로 호출된다. */
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

    /** 데이터를 교체한다. 확대한 보기는 그대로 두고, 전체 보기는 새 데이터를 따라간다. */
    void setData(List<List<Metrics.Sample>> newData) {
        data = newData;
        XYChart chart = getChart();
        for (MetricKind.Series meta : kind.series()) {
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
            MetricKind.Series meta = kind.series().get(i);
            XYSeries xy = chart.addSeries(meta.name(), xs, ys);
            xy.setLineColor(meta.color());
            xy.setLineStyle(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            xy.setMarker(SeriesMarkers.NONE);
            if (filled) {
                // 선 아래를 시리즈 색의 18% 농도로 채운다(시안의 옅은 영역).
                xy.setXYSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Area);
                xy.setFillColor(new Color(meta.color().getRed(), meta.color().getGreen(), meta.color().getBlue(), 46));
            }
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

    /** 축을 보기에 맞추고, y 범위를 보이는 것에 맞춘다. */
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

    /** 마우스가 플롯 안에 있으면 그 시각을 공유하고, 벗어났으면(축 글자 영역 등) 지운다. */
    private void shareHover(int x) {
        if (sync == null || window == null || !painted) {
            return;
        }
        if (x < plotLeft() || x > plotRight()) {
            sync.clear();
            return;
        }
        sync.hover(Math.round(getChart().getChartXFromCoordinate(x)));
    }

    /** 지금 가리키는 시각. 공유 중이면 공유된 시각(다른 차트 위의 마우스도 포함), 아니면 이 차트의 마우스 위치. 없으면 null. */
    Long hoverTime() {
        if (sync != null) {
            return sync.time();
        }
        if (mouseX < 0 || window == null || !painted || mouseX < plotLeft() || mouseX > plotRight()) {
            return null;
        }
        return Math.round(getChart().getChartXFromCoordinate(mouseX));
    }

    /** 알림 기준선: 설정한 %의 높이에 주황 점선과 "알림 기준 90%" 글자. */
    private void paintAlertLine(Graphics g) {
        if (alertThreshold == null || kind.yMax() == null || alertThreshold > yTop) {
            return;
        }
        int y = (int) Math.round(getChart().getScreenYFromChart(alertThreshold));
        int left = (int) Math.round(plotLeft());
        int right = (int) Math.round(plotRight());
        java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
        try {
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(Theme.WARNING_BAR);
            g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {5f, 4f}, 0f));
            g2.drawLine(left, y, right, y);
            g2.setFont(getFont().deriveFont(11f));
            String label = "알림 기준 " + Math.round(alertThreshold) + "%";
            g2.drawString(label, right - g2.getFontMetrics().stringWidth(label) - 6, y - 5);
        } finally {
            g2.dispose();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        painted = true;
        if (window == null || data.isEmpty()) {
            return;
        }
        paintAlertLine(g);
        Long hovered = hoverTime();
        // 가리키는 시각이 이 차트에 보이는 범위 밖이면(확대해서 다른 구간을 보는 중) 그리지 않는다.
        if (hovered == null || hovered < window.min() || hovered > window.max()) {
            return;
        }
        long target = hovered;
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
