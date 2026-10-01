package com.infradesk.ui.components;

import com.infradesk.core.ServerStatus;
import com.infradesk.ui.Theme;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import javax.swing.Icon;

/** 8px 상태 점: 실행 중이면 채운 초록, 정지면 회색 윤곽선, 전이 중이면 호박색. */
public class StatusDot implements Icon {

    private static final int SIZE = 8;
    private final ServerStatus status;

    public StatusDot(ServerStatus status) {
        this.status = status;
    }

    public static Color colorOf(ServerStatus status) {
        if (status == ServerStatus.RUNNING) {
            return Theme.RUNNING_DOT;
        }
        if (status.isTransitional()) {
            return Theme.WARNING;
        }
        return Theme.TEXT_MUTED;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(colorOf(status));
            if (status == ServerStatus.RUNNING || status.isTransitional()) {
                g2.fill(new Ellipse2D.Float(x, y, SIZE, SIZE));
            } else {
                g2.setStroke(new BasicStroke(1.5f));
                g2.draw(new Ellipse2D.Float(x + 0.75f, y + 0.75f, SIZE - 1.5f, SIZE - 1.5f));
            }
        } finally {
            g2.dispose();
        }
    }

    @Override
    public int getIconWidth() {
        return SIZE;
    }

    @Override
    public int getIconHeight() {
        return SIZE;
    }
}
