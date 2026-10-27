package com.infradesk.ui.metrics;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.core.Metrics;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.RoundedPanel;

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
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * 모니터링 탭의 큰 차트 카드 (디자인 v2): 제목과 지금 값, (선이 둘이면) 범례, 그리고 축이 있는 큰 차트.
 *
 * <p>차트는 {@link ZoomChartPanel}이다: 휠로 확대·축소, 끌어서 이동, 두 번 눌러 처음 범위. 지금 값은
 * 가장 최근 점이고, 선이 둘(수신·송신)이면 합계다.
 */
final class MetricChartCard extends RoundedPanel {

    private static final int CHART_HEIGHT = 200;

    private final MetricKind kind;
    private final JLabel value = new JLabel("—");
    private final JLabel empty = new JLabel("", SwingConstants.CENTER);
    private final ZoomChartPanel chart;
    private final CardLayout cards = new CardLayout();
    private final JPanel plot = new JPanel(cards);

    MetricChartCard(MetricKind kind) {
        super(new BorderLayout(0, 6), Theme.PANEL_BG, Theme.DIVIDER, Theme.ARC_CARD);
        this.kind = kind;
        setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 16));

        JLabel title = new JLabel(kind.title());
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        title.setForeground(Theme.TEXT_SECONDARY);
        value.setFont(value.getFont().deriveFont(Font.BOLD, 20f));
        value.setForeground(Theme.TEXT);
        JPanel titleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        titleRow.setOpaque(false);
        titleRow.add(title);
        titleRow.add(value);

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(titleRow, BorderLayout.WEST);
        if (kind.series().size() > 1) {
            header.add(legend(), BorderLayout.EAST);
        }

        chart = new ZoomChartPanel(kind);
        chart.setFilled(true);
        chart.setLegendVisible(false);   // 범례는 카드 머리에 둔다
        empty.setForeground(Theme.TEXT_MUTED);
        empty.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        plot.setOpaque(false);
        plot.add(chart, "chart");
        plot.add(empty, "empty");
        plot.setPreferredSize(new Dimension(100, CHART_HEIGHT));

        add(header, BorderLayout.NORTH);
        add(plot, BorderLayout.CENTER);
        showEmpty("불러오는 중…");
    }

    /** 범례: 선 색의 짧은 가로선과 이름. 값은 카드 머리의 합계 하나로 보여 준다. */
    private JPanel legend() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 0));
        row.setOpaque(false);
        for (MetricKind.Series s : kind.series()) {
            JLabel l = new JLabel(s.name(), new Dash(s.color()), SwingConstants.LEFT);
            l.setForeground(Theme.TEXT_SECONDARY);
            l.setIconTextGap(6);
            row.add(l);
        }
        return row;
    }

    /**
     * 데이터를 보여 준다. {@code data}에는 시리즈마다 목록 하나가 {@link MetricKind#series()} 순서대로 들어 있다.
     * 점이 하나도 없으면 {@code emptyText}를 보여 준다.
     */
    void show(List<List<Metrics.Sample>> data, String emptyText) {
        if (data.stream().allMatch(List::isEmpty)) {
            showEmpty(emptyText);
            return;
        }
        chart.setData(data);
        value.setText(currentText(data));
        cards.show(plot, "chart");
    }

    void showEmpty(String message) {
        value.setText("—");
        empty.setText(message);
        cards.show(plot, "empty");
    }

    /** 지금 값: 선이 하나면 가장 최근 점, 둘이면(수신·송신) 최근 점의 합. */
    private String currentText(List<List<Metrics.Sample>> data) {
        if (kind.series().size() == 1) {
            return data.getFirst().isEmpty() ? "—" : kind.format().apply(data.getFirst().getLast().value());
        }
        return kind.format().apply(data.stream().filter(d -> !d.isEmpty()).mapToDouble(d -> d.getLast().value()).sum());
    }

    /** 지금 값 글자(테스트용). */
    String valueText() {
        return value.getText();
    }

    boolean showsChart() {
        return plot.getComponent(0).isVisible();
    }

    ZoomChartPanel chart() {
        return chart;
    }

    @Override
    public void setAlignmentX(float alignmentX) {
        super.setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    /** 범례 견본: 선 색의 둥근 가로선. */
    private record Dash(Color color) implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                g2.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(x + 1, y + 5, x + 11, y + 5);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return 12;
        }

        @Override
        public int getIconHeight() {
            return 10;
        }
    }
}
