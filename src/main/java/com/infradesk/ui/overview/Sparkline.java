package com.infradesk.ui.overview;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.GeneralPath;
import java.util.List;
import javax.swing.JComponent;

/**
 * 카드 아래에 깔리는 작은 선 그래프 (디자인 v2 "지금 상태"). 축과 숫자는 없고, 선과 그 아래 옅은 채움만 그린다.
 * 자세한 값은 모니터링 탭이 보여 주므로 여기서는 "최근에 오르내렸는지"만 느끼게 한다.
 */
final class Sparkline extends JComponent {

    private static final int HEIGHT = 40;

    private List<Double> values = List.of();
    private double max;
    private Color color = Color.GRAY;

    Sparkline() {
        setOpaque(false);
    }

    /**
     * @param values 오래된 것부터 정렬된 값. 두 개 미만이면 아무것도 그리지 않는다
     * @param max    세로 눈금의 맨 위 값. 0 이하이면 값 중 가장 큰 것(사용률은 100으로 고정해서 호출한다)
     */
    void set(List<Double> values, double max, Color color) {
        this.values = List.copyOf(values);
        this.max = max;
        this.color = color;
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(10, HEIGHT);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, HEIGHT);
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (values.size() < 2) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double top = max > 0 ? max : values.stream().mapToDouble(Double::doubleValue).max().orElse(1);
            if (top <= 0) {
                top = 1;
            }
            int w = getWidth();
            int h = getHeight() - 2;
            GeneralPath line = new GeneralPath();
            for (int i = 0; i < values.size(); i++) {
                double x = w * (double) i / (values.size() - 1);
                double y = h - h * Math.max(0, Math.min(1, values.get(i) / top)) + 1;
                if (i == 0) {
                    line.moveTo(x, y);
                } else {
                    line.lineTo(x, y);
                }
            }
            GeneralPath area = new GeneralPath(line);
            area.lineTo(w, getHeight());
            area.lineTo(0, getHeight());
            area.closePath();
            g2.setColor(color);
            g2.setComposite(AlphaComposite.SrcOver.derive(0.22f));
            g2.fill(area);
            g2.setComposite(AlphaComposite.SrcOver);
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(line);
        } finally {
            g2.dispose();
        }
    }
}
