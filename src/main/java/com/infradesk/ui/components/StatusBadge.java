package com.infradesk.ui.components;

import com.infradesk.core.ServerStatus;
import com.infradesk.ui.Theme;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.BorderFactory;
import javax.swing.JLabel;

/** Pill-shaped status label ("실행 중"). */
public class StatusBadge extends JLabel {

    private Color pill = Theme.PANEL_BG;

    public StatusBadge() {
        setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        putClientProperty("FlatLaf.style", "font: -1");
    }

    public void setStatus(ServerStatus status) {
        setText(status.label());
        if (status == ServerStatus.RUNNING) {
            pill = Theme.RUNNING_BADGE_BG;
            setForeground(Theme.RUNNING_BADGE_TEXT);
        } else if (status.isTransitional()) {
            pill = new Color(0x3A3220);
            setForeground(Theme.WARNING);
        } else {
            pill = new Color(0x34363B);
            setForeground(Theme.TEXT_SECONDARY);
        }
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(d.width, Math.max(d.height, 20));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(pill);
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
        g2.dispose();
        super.paintComponent(g);
    }
}
