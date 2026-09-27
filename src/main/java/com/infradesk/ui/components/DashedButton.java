package com.infradesk.ui.components;

import com.infradesk.ui.Icons;
import com.infradesk.ui.Theme;

import java.awt.BasicStroke;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import javax.swing.JButton;

/** Transparent button with a dashed rounded border ("계정 추가"). Keyboard focusable. */
public class DashedButton extends JButton {

    public DashedButton(String text, String icon) {
        super(text, Icons.get(icon, 14));
        setForeground(Theme.TEXT_SECONDARY);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setIconTextGap(6);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setPreferredSize(new Dimension(0, Theme.BUTTON_HEIGHT));
        getModel().addChangeListener(e -> repaint());
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            var shape = new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f,
                    Theme.ARC_CONTROL * 2f, Theme.ARC_CONTROL * 2f);
            if (getModel().isRollover()) {
                g2.setColor(Theme.APP_BG);
                g2.fill(shape);
            }
            boolean highlight = getModel().isRollover() || isFocusOwner();
            g2.setColor(highlight ? Theme.TEXT_MUTED : Theme.DASHED_BORDER);
            g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {4f, 3f}, 0f));
            g2.draw(shape);
        } finally {
            g2.dispose();
        }
        super.paintComponent(g);
    }
}
