package com.infradesk.ui.metrics;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.core.Metrics;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.RoundedPanel;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.XYSeries;
import org.knowm.xchart.style.XYStyler;
import org.knowm.xchart.style.markers.SeriesMarkers;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.DoubleFunction;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * 통계 타일: 제목, 현재 값, 최근 값의 스파크라인(XChart)과 호버 십자선. 시리즈는 한두 개이며,
 * 두 개이면 항목마다 값이 적힌 범례 줄이 붙는다.
 */
public class MetricCard extends RoundedPanel {

    /** 차트의 선 하나. */
    public record Series(String name, Color color) {
    }

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter SECOND = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final List<Series> series;
    private final DoubleFunction<String> format;
    private final Double yMax;
    private final JLabel value = new JLabel("—");
    private final JLabel caption = new JLabel(" ");
    private final JLabel empty = new JLabel("", SwingConstants.CENTER);
    private final List<JLabel> legend;
    private final XYChart chart;
    private final HoverChartPanel chartPanel;
    private final CardLayout plotCards = new CardLayout();
    private final JPanel plot = new JPanel(plotCards);

    public static MetricCard of(MetricKind kind) {
        return new MetricCard(kind.title(), kind.series(), kind.format(), kind.yMax());
    }

    /**
     * @param yMax y 범위의 고정된 상단(예: 백분율은 100). 데이터에 맞추려면 null
     */
    public MetricCard(String title, List<Series> series, DoubleFunction<String> format, Double yMax) {
        super(new BorderLayout(0, 8), Theme.PANEL_BG, Theme.DIVIDER, Theme.ARC_CARD);
        this.series = List.copyOf(series);
        this.format = format;
        this.yMax = yMax;
        setBorder(BorderFactory.createEmptyBorder(12, 14, 10, 14));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setForeground(Theme.TEXT_SECONDARY);
        value.setFont(value.getFont().deriveFont(Font.BOLD, 20f));
        value.setForeground(Theme.TEXT);
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        JLabel expandHint = new JLabel("⤢");
        expandHint.setForeground(Theme.TEXT_MUTED);
        expandHint.setToolTipText("클릭하면 크게 보고 확대·축소할 수 있어요");
        JPanel titleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        titleRow.setOpaque(false);
        titleRow.add(titleLabel);
        titleRow.add(expandHint);
        header.add(titleRow, BorderLayout.WEST);
        header.add(value, BorderLayout.EAST);

        chart = new XYChartBuilder().width(300).height(56).build();
        styleChart();
        chartPanel = new HoverChartPanel(chart, this.series, format);
        chartPanel.setOpaque(false);
        chartPanel.setBackground(Theme.PANEL_BG);
        chartPanel.setPreferredSize(new Dimension(100, 56));
        chartPanel.setMinimumSize(new Dimension(50, 56));
        chartPanel.setComponentPopupMenu(null);
        for (var l : chartPanel.getMouseListeners()) {
            if (l.getClass().getName().contains("PopUpMenu")) {
                chartPanel.removeMouseListener(l);
            }
        }
        empty.setForeground(Theme.TEXT_MUTED);
        empty.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        plot.setOpaque(false);
        plot.add(chartPanel, "chart");
        plot.add(empty, "empty");
        plot.setPreferredSize(new Dimension(100, 56));

        JPanel footer = new JPanel(new BorderLayout(0, 4));
        footer.setOpaque(false);
        caption.setForeground(Theme.TEXT_MUTED);
        caption.putClientProperty(FlatClientProperties.STYLE, "font: -2");
        footer.add(caption, BorderLayout.SOUTH);
        if (series.size() > 1) {
            JPanel legendRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            legendRow.setOpaque(false);
            legend = series.stream().map(s -> {
                JLabel l = new JLabel(s.name(), new Dot(s.color()), SwingConstants.LEFT);
                l.setForeground(Theme.TEXT_SECONDARY);
                l.putClientProperty(FlatClientProperties.STYLE, "font: -2");
                l.setIconTextGap(5);
                l.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 14));
                legendRow.add(l);
                return l;
            }).toList();
            footer.add(legendRow, BorderLayout.NORTH);
        } else {
            legend = List.of();
        }

        add(header, BorderLayout.NORTH);
        add(plot, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
        showEmpty("불러오는 중…");
    }

    private void styleChart() {
        XYStyler st = chart.getStyler();
        st.setChartBackgroundColor(Theme.PANEL_BG);
        st.setPlotBackgroundColor(Theme.PANEL_BG);
        st.setPlotBorderVisible(false);
        st.setPlotGridLinesVisible(false);
        st.setAxisTicksVisible(false);
        st.setAxisTitlesVisible(false);
        st.setLegendVisible(false);
        st.setChartTitleVisible(false);
        st.setChartPadding(0);
        st.setPlotMargin(2);
        st.setMarkerSize(0);
        st.setAntiAlias(true);
        st.setYAxisMin(0.0);
        if (yMax != null) {
            st.setYAxisMax(yMax);
        }
        st.setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);
        for (Series s : series) {
            XYSeries xy = chart.addSeries(s.name(), new double[] {0, 1}, new double[] {0, 0});
            xy.setLineColor(s.color());
            xy.setLineStyle(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            xy.setMarker(SeriesMarkers.NONE);
        }
    }

    /**
     * 데이터를 보여준다. {@code data}에는 시리즈마다 목록 하나가 생성자 순서대로 들어 있다.
     *
     * @param secondsResolution 실시간 샘플이면 true(툴팁에 초가 표시된다)
     */
    public void show(List<List<Metrics.Sample>> data, String captionText, boolean secondsResolution, String emptyText) {
        caption.setText(captionText);
        boolean any = data.stream().anyMatch(d -> !d.isEmpty());
        if (!any) {
            showEmpty(emptyText);
            return;
        }
        for (int i = 0; i < series.size(); i++) {
            List<Metrics.Sample> d = data.get(i);
            if (d.isEmpty()) {
                chart.updateXYSeries(series.get(i).name(), new double[] {0}, new double[] {0}, null);
                continue;
            }
            double[] x = new double[d.size()];
            double[] y = new double[d.size()];
            for (int j = 0; j < d.size(); j++) {
                x[j] = d.get(j).time().toEpochMilli();
                y[j] = d.get(j).value();
            }
            chart.updateXYSeries(series.get(i).name(), x, y, null);
        }
        if (series.size() == 1) {
            value.setText(format.apply(data.getFirst().getLast().value()));
        } else {
            value.setText(format.apply(data.stream().filter(d -> !d.isEmpty()).mapToDouble(d -> d.getLast().value()).sum()));
            for (int i = 0; i < series.size(); i++) {
                List<Metrics.Sample> d = data.get(i);
                legend.get(i).setText(series.get(i).name() + " " + (d.isEmpty() ? "—" : format.apply(d.getLast().value())));
            }
        }
        chartPanel.setData(data, secondsResolution ? SECOND : MINUTE);
        plotCards.show(plot, "chart");
        plot.repaint();
    }

    public void showEmpty(String message) {
        value.setText("—");
        empty.setText(message);
        for (int i = 0; i < legend.size(); i++) {
            legend.get(i).setText(series.get(i).name());
        }
        plotCards.show(plot, "empty");
    }

    /** 카드(차트와 헤더)를 클릭하면 확대 차트가 열리게 한다. */
    public void onClick(Runnable action) {
        java.awt.event.MouseAdapter click = new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getButton() == java.awt.event.MouseEvent.BUTTON1) {
                    action.run();
                }
            }
        };
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        addMouseListener(click);
        chartPanel.addMouseListener(click);
        chartPanel.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
    }

    @Override
    public void setAlignmentX(float alignmentX) {
        super.setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    /** 범례 견본: 시리즈 색이 구분을 담당하고, 라벨 글자는 본문 색을 유지한다. */
    private record Dot(Color color) implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillRoundRect(x, y + 3, 10, 3, 3, 3);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 10;
        }

        @Override
        public int getIconHeight() {
            return 9;
        }
    }
}
