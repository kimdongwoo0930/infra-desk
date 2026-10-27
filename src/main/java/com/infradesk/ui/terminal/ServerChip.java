package com.infradesk.ui.terminal;

import com.infradesk.ui.Theme;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import javax.swing.JCheckBox;

/**
 * "여러 서버에서 실행"의 서버 선택 칩 (디자인 v2): 네모 체크 + 서버 이름이 알약 안에 든다. 고르면 옅은 파랑 바탕에
 * 파랑 테두리, 꺼진 서버는 흐리게 비활성이다. 체크박스라서 {@code isSelected()}로 고른 여부를 읽는다.
 */
final class ServerChip extends JCheckBox {

    private static final int HEIGHT = 30;
    private static final int PAD_X = 10;
    private static final int BOX = 14;
    private static final int GAP = 8;

    ServerChip(String label) {
        super(label);
        setOpaque(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setFont(getFont().deriveFont(12.5f));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        return new Dimension(PAD_X + BOX + GAP + fm.stringWidth(getText()) + PAD_X, HEIGHT);
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean on = isSelected() && isEnabled();
            var chip = new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, 16, 16);
            if (on) {
                g2.setColor(Theme.ACCENT_SOFT_BG);
                g2.fill(chip);
            }
            g2.setColor(on ? Theme.ACCENT_LINE : Theme.DIVIDER);
            g2.draw(chip);

            int boxY = (getHeight() - BOX) / 2;
            var box = new RoundRectangle2D.Float(PAD_X + 0.5f, boxY + 0.5f, BOX - 1f, BOX - 1f, 5, 5);
            if (isSelected()) {
                g2.setColor(isEnabled() ? Theme.ACCENT : Theme.DIVIDER);
                g2.fill(box);
                g2.setColor(isEnabled() ? java.awt.Color.WHITE : Theme.TEXT_MUTED);
                g2.setStroke(new java.awt.BasicStroke(1.8f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
                g2.drawPolyline(new int[] {PAD_X + 3, PAD_X + 6, PAD_X + 11}, new int[] {boxY + 7, boxY + 10, boxY + 4}, 3);
            } else {
                g2.setColor(isEnabled() ? Theme.TEXT_MUTED : Theme.DIVIDER);
                g2.draw(box);
            }

            g2.setFont(getFont());
            g2.setColor(isEnabled() ? Theme.TEXT : Theme.TEXT_MUTED);
            FontMetrics fm = g2.getFontMetrics();
            int baseline = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(getText(), PAD_X + BOX + GAP, baseline);
        } finally {
            g2.dispose();
        }
    }
}
