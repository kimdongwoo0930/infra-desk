package com.infradesk.ui.components;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import javax.swing.JPanel;

/** Panel with a rounded background and optional 1px border. */
public class RoundedPanel extends JPanel {

    private final int arc;
    private Color borderColor;
    private boolean dashed;

    public RoundedPanel(LayoutManager layout, Color background, Color borderColor, int arc) {
        super(layout);
        this.arc = arc;
        this.borderColor = borderColor;
        setBackground(background);
        setOpaque(false);
    }

    public void setBorderColor(Color borderColor) {
        this.borderColor = borderColor;
        repaint();
    }

    public void setDashed(boolean dashed) {
        this.dashed = dashed;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            var shape = new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, arc * 2f, arc * 2f);
            if (getBackground() != null) {
                g2.setColor(getBackground());
                g2.fill(shape);
            }
            if (borderColor != null) {
                g2.setColor(borderColor);
                g2.setStroke(dashed
                        ? new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {4f, 3f}, 0f)
                        : new BasicStroke(1f));
                g2.draw(shape);
            }
        } finally {
            g2.dispose();
        }
        super.paintComponent(g);
    }
}
