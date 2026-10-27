package com.infradesk.ui.components;

import com.infradesk.ui.Theme;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import javax.swing.JLabel;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatusBarTest {

    private static StatusBar bar() {
        StatusBar bar = new StatusBar();
        bar.addLeft("실시간 꺼짐");
        bar.addLeft("전송 없음");
        bar.addRight("상태 30초 · 메트릭 1분마다");
        bar.addRight("v1.1.9");
        return bar;
    }

    @Test
    void 높이는_26이고_폭은_레이아웃이_정한다() {
        assertEquals(26, bar().getPreferredSize().height);
        assertEquals(Integer.MAX_VALUE, bar().getMaximumSize().width);
    }

    @Test
    void 위쪽에_구분선이_있고_나머지는_패널_배경이다() {
        StatusBar bar = bar();
        bar.setSize(500, 26);
        bar.doLayout();
        BufferedImage img = new BufferedImage(500, 26, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        try {
            bar.paint(g);
        } finally {
            g.dispose();
        }
        assertEquals(Theme.DIVIDER.getRGB(), img.getRGB(250, 0));
        assertEquals(Theme.PANEL_BG.getRGB(), img.getRGB(250, 13));
    }

    @Test
    void 돌려받은_라벨로_글자를_바꿀_수_있다() {
        StatusBar bar = new StatusBar();
        JLabel live = bar.addLeft("실시간 꺼짐");
        assertEquals(Theme.TEXT_MUTED, live.getForeground());
        live.setText("실시간 켜짐");
        assertEquals("실시간 켜짐", live.getText());
    }
}
