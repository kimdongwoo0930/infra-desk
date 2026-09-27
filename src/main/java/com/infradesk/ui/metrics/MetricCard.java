package com.infradesk.ui.metrics;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.core.Metrics;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.RoundedPanel;
import org.knowm.xchart.XChartPanel;
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
import java.time.Instant;
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
 * Stat tile: title, current value, and a sparkline of recent values (XChart) with a hover
 * crosshair. One or two series; two series get a legend row whose entries carry the values.
 */
public class MetricCard extends RoundedPanel {

    /** One line on the chart. */
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
    private final CardLayout plotCards = new CardLayout();
    private final JPanel plot = new JPanel(plotCards);
    private boolean seconds;

    /**
     * @param yMax fixed top of the y range (e.g. 100 for percentages), or null to fit the data
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
        header.add(titleLabel, BorderLayout.WEST);
        header.add(value, BorderLayout.EAST);

        chart = new XYChartBuilder().width(300).height(56).build();
        styleChart();
        XChartPanel<XYChart> chartPanel = new XChartPanel<>(chart);
        chartPanel.setCursorEnabled(true);
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
        st.setCursorColor(Theme.TEXT_MUTED);
        st.setCursorLineWidth(1f);
        st.setCursorBackgroundColor(Theme.APP_BG);
        st.setCursorFontColor(Theme.TEXT);
        st.setCursorFont(new JLabel().getFont().deriveFont(11f));
        st.setCustomCursorXDataFormattingFunction(x -> (seconds ? SECOND : MINUTE).format(Instant.ofEpochMilli(x.longValue())));
        st.setCustomCursorYDataFormattingFunction(format::apply);
        for (Series s : series) {
            XYSeries xy = chart.addSeries(s.name(), new double[] {0, 1}, new double[] {0, 0});
            xy.setLineColor(s.color());
            xy.setLineStyle(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            xy.setMarker(SeriesMarkers.NONE);
        }
    }

    /**
     * Shows data. {@code data} holds one list per series, in constructor order.
     *
     * @param secondsResolution true for live samples (tooltip shows seconds)
     */
    public void show(List<List<Metrics.Sample>> data, String captionText, boolean secondsResolution, String emptyText) {
        this.seconds = secondsResolution;
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

    @Override
    public void setAlignmentX(float alignmentX) {
        super.setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    /** Legend swatch: the series color carries identity, the label text stays in text ink. */
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
