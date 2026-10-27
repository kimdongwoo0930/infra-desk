package com.infradesk.ui.components;

import com.infradesk.ui.Theme;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import javax.swing.JToggleButton;

/**
 * 알약 모양의 거르기 토글 ("전체 6", "실행 중 4" …). 시안: 높이 28, 글자 12, 좌우 여백 10.
 *
 * <p>선택되면 옅은 파랑 배경({@code ACCENT_SOFT_BG})에 파랑 테두리({@code ACCENT_LINE}),
 * 선택되지 않으면 투명 배경에 회색 테두리다. 글자 뒤의 개수는 흐린 색으로 따로 그린다.
 * 여러 칩 중 하나만 선택되게 하려면 {@link javax.swing.ButtonGroup}에 넣는다.
 */
public class FilterChip extends JToggleButton {

    private static final int HEIGHT = 28;
    private static final int PAD_X = 10;
    /** 글자와 개수 사이 간격. */
    private static final int COUNT_GAP = 4;

    /** 글자 앞의 상태 점 지름과, 점과 글자 사이 간격. */
    private static final int DOT = 8;
    private static final int DOT_GAP = 6;

    private Integer count;
    /** null이면 점 없음, TRUE면 채운 초록 점(실행 중), FALSE면 테두리만 있는 점(정지). */
    private Boolean dot;

    public FilterChip(String label) {
        super(label);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        // 마우스를 올렸을 때(rollover)를 알려 받으려면 켜 두어야 한다.
        setRolloverEnabled(true);
        setFont(getFont().deriveFont(12f));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    /** 글자 뒤에 흐리게 붙는 개수. null이면 표시하지 않는다. */
    public void setCount(Integer count) {
        this.count = count;
        revalidate();
        repaint();
    }

    public Integer getCount() {
        return count;
    }

    /** 글자 앞에 상태 점을 붙인다: 실행 중(true)은 채운 초록 점, 정지(false)는 테두리만 있는 점, null은 없음. */
    public void setStatusDot(Boolean running) {
        this.dot = running;
        revalidate();
        repaint();
    }

    public Boolean getStatusDot() {
        return dot;
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        int width = PAD_X * 2 + fm.stringWidth(getText());
        if (dot != null) {
            width += DOT + DOT_GAP;
        }
        if (count != null) {
            width += COUNT_GAP + fm.stringWidth(String.valueOf(count));
        }
        return new Dimension(width, HEIGHT);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            if (!isEnabled()) {
                g2.setComposite(AlphaComposite.SrcOver.derive(0.45f));
            }
            int w = getWidth();
            int h = getHeight();
            boolean selected = isSelected();

            // 선을 정확히 한 픽셀 두께로 그리려고 경계를 0.5 안쪽에 둔다(선은 경계 양쪽으로 반씩 번진다).
            RoundRectangle2D shape = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, h - 1, h - 1);
            if (selected) {
                g2.setColor(Theme.ACCENT_SOFT_BG);
                g2.fill(shape);
            } else if (getModel().isRollover() && isEnabled()) {
                g2.setColor(Theme.HOVER_BG);
                g2.fill(shape);
            }
            g2.setColor(selected ? Theme.ACCENT_LINE : Theme.INPUT_BORDER);
            g2.setStroke(new BasicStroke(1f));
            g2.draw(shape);

            // 글자는 세로 가운데에 놓는다: 기준선(baseline) = 위 여백 + 글자 올림(ascent).
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            int baseline = (h - fm.getHeight()) / 2 + fm.getAscent();
            int x = PAD_X;
            if (dot != null) {
                int dotY = (h - DOT) / 2;
                if (dot) {
                    g2.setColor(Theme.RUNNING_DOT);
                    g2.fillOval(x, dotY, DOT, DOT);
                } else {
                    // 정지: 속이 빈 점. 선 두께 1.2의 절반만큼 안쪽으로 그려서 지름이 8을 넘지 않게 한다.
                    g2.setColor(Theme.TEXT_MUTED);
                    g2.setStroke(new BasicStroke(1.2f));
                    g2.drawOval(x, dotY, DOT - 1, DOT - 1);
                }
                x += DOT + DOT_GAP;
            }
            g2.setColor(selected ? Theme.TEXT : Theme.TEXT_SECONDARY);
            g2.drawString(getText(), x, baseline);
            if (count != null) {
                g2.setColor(Theme.TEXT_MUTED);
                g2.drawString(String.valueOf(count), x + fm.stringWidth(getText()) + COUNT_GAP, baseline);
            }
        } finally {
            g2.dispose();
        }
    }
}
