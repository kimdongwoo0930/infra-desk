package com.infradesk.ui.components;

import com.infradesk.ui.Theme;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JComponent;

/**
 * 사용률 막대 (CPU·메모리·디스크). 시안: 높이 6의 트랙({@code DIVIDER}) 위에 사용률만큼
 * 파랑({@code ACCENT_LINE})으로 채우고, 85% 이상이면 경고색({@code WARNING_BAR})으로 바꾼다.
 *
 * <p>숫자(예: "42%")는 이 부품이 그리지 않는다. 옆에 라벨을 따로 둔다. 다만 색만으로 경고를
 * 전하지 않도록(디자인 v2 규칙), 호출하는 쪽에서 경고일 때 숫자도 강조해야 한다 — 그 판단에
 * 쓰라고 {@link #isWarning()}을 열어 두었다.
 */
public class Meter extends JComponent {

    /** 이 값 이상이면 경고색. */
    public static final int WARN_AT = 85;

    private static final int HEIGHT = 6;

    private double percent;

    public Meter() {
        setOpaque(false);
    }

    /** 0~100으로 맞춰서 저장한다(범위를 벗어나거나 NaN이면 0). */
    public void setPercent(double percent) {
        this.percent = Double.isNaN(percent) ? 0 : Math.max(0, Math.min(100, percent));
        repaint();
    }

    public double getPercent() {
        return percent;
    }

    public boolean isWarning() {
        return percent >= WARN_AT;
    }

    // 높이는 6으로 고정하고 폭은 레이아웃이 정한다. 쓰는 쪽이 setPreferredSize로 폭을 정했으면 그것을 따른다
    // (getPreferredSize를 그냥 덮어쓰면 setPreferredSize가 무시된다).
    @Override
    public Dimension getPreferredSize() {
        if (isPreferredSizeSet()) {
            return super.getPreferredSize();
        }
        return new Dimension(120, HEIGHT);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(20, HEIGHT);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, HEIGHT);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int y = (getHeight() - HEIGHT) / 2;

            // 트랙. 반지름 3(= 높이의 절반)이라 arc(지름)는 높이와 같은 6이다.
            g2.setColor(Theme.DIVIDER);
            g2.fillRoundRect(0, y, w, HEIGHT, HEIGHT, HEIGHT);

            int fillWidth = (int) Math.round(w * percent / 100.0);
            if (fillWidth > 0) {
                g2.setColor(isWarning() ? Theme.WARNING_BAR : Theme.ACCENT_LINE);
                g2.fillRoundRect(0, y, fillWidth, HEIGHT, HEIGHT, HEIGHT);
            }
        } finally {
            g2.dispose();
        }
    }
}
