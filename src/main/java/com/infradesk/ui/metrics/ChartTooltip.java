package com.infradesk.ui.metrics;

import com.infradesk.ui.Theme;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.List;

/** Vertical hover line plus one box naming the time and each series' value, shared by the chart panels. */
final class ChartTooltip {

    private ChartTooltip() {
    }

    /**
     * @param lineX      x of the hover line
     * @param lineTop    top of the hover line
     * @param lineBottom bottom of the hover line
     * @param boxY       top of the box
     * @param lines      one text per series, coloured with the matching entry of {@code colors}
     */
    static void draw(Graphics g, Font baseFont, int panelWidth, int lineX, int lineTop, int lineBottom, int boxY,
                     String header, List<String> lines, List<Color> colors) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(Theme.TEXT_MUTED);
            g2.setStroke(new BasicStroke(1f));
            g2.drawLine(lineX, lineTop, lineX, lineBottom);

            g2.setFont(baseFont.deriveFont(11f));
            FontMetrics fm = g2.getFontMetrics();
            int swatch = 10;
            int width = fm.stringWidth(header);
            for (String l : lines) {
                width = Math.max(width, swatch + 5 + fm.stringWidth(l));
            }
            int pad = 6;
            int lineH = fm.getHeight();
            int boxW = width + pad * 2;
            int boxH = lineH * (lines.size() + 1) + pad;
            int boxX = lineX + 8 + boxW <= panelWidth ? lineX + 8 : lineX - 8 - boxW;
            boxX = Math.max(0, Math.min(boxX, panelWidth - boxW));

            g2.setColor(Theme.APP_BG);
            g2.fillRoundRect(boxX, boxY, boxW, boxH, 8, 8);
            g2.setColor(Theme.INPUT_BORDER);
            g2.drawRoundRect(boxX, boxY, boxW - 1, boxH - 1, 8, 8);

            int y = boxY + pad / 2 + fm.getAscent();
            g2.setColor(Theme.TEXT_MUTED);
            g2.drawString(header, boxX + pad, y);
            for (int i = 0; i < lines.size(); i++) {
                y += lineH;
                g2.setColor(colors.get(i));
                g2.fillRoundRect(boxX + pad, y - fm.getAscent() / 2 - 1, swatch, 3, 3, 3);
                g2.setColor(Theme.TEXT);
                g2.drawString(lines.get(i), boxX + pad + swatch + 5, y);
            }
        } finally {
            g2.dispose();
        }
    }
}
