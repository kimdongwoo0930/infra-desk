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

/** 세로 호버선과, 시각과 각 시리즈의 값을 적은 상자 하나. 차트 패널들이 함께 쓴다. */
final class ChartTooltip {

    private ChartTooltip() {
    }

    /**
     * @param lineX      호버선의 x
     * @param lineTop    호버선의 위쪽
     * @param lineBottom 호버선의 아래쪽
     * @param boxY       상자의 위쪽
     * @param lines      시리즈마다 텍스트 하나. {@code colors}의 해당 항목 색으로 칠한다
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
