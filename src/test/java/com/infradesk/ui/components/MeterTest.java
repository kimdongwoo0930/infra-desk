package com.infradesk.ui.components;

import com.infradesk.ui.Theme;

import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeterTest {

    @Test
    void 범위를_벗어난_값은_0에서_100으로_맞춘다() {
        Meter m = new Meter();
        m.setPercent(150);
        assertEquals(100, m.getPercent());
        m.setPercent(-5);
        assertEquals(0, m.getPercent());
        m.setPercent(Double.NaN);
        assertEquals(0, m.getPercent());
    }

    @Test
    void 경고는_85퍼센트부터다() {
        Meter m = new Meter();
        m.setPercent(84.9);
        assertFalse(m.isWarning());
        m.setPercent(85);
        assertTrue(m.isWarning());
    }

    @Test
    void 사용률만큼_파랑으로_채우고_나머지는_트랙색이다() {
        Meter m = new Meter();
        m.setPercent(50);
        BufferedImage img = paint(m);
        assertEquals(Theme.ACCENT_LINE.getRGB(), img.getRGB(2, 3));    // 채운 쪽
        assertEquals(Theme.DIVIDER.getRGB(), img.getRGB(110, 3));      // 빈 쪽
    }

    @Test
    void 쓰는_쪽이_정한_폭을_따른다() {
        Meter m = new Meter();
        assertEquals(120, m.getPreferredSize().width);   // 정하지 않으면 기본 폭
        m.setPreferredSize(new Dimension(80, 6));
        assertEquals(80, m.getPreferredSize().width);
    }

    @Test
    void 경고일_때는_경고색으로_채운다() {
        Meter m = new Meter();
        m.setPercent(90);
        assertEquals(Theme.WARNING_BAR.getRGB(), paint(m).getRGB(2, 3));
    }

    /** 폭 120, 높이 6으로 그린다(레이아웃이 폭을 정하는 상황을 흉내 낸다). */
    private static BufferedImage paint(Meter m) {
        m.setSize(new Dimension(120, 6));
        BufferedImage image = new BufferedImage(120, 6, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();
        try {
            m.paint(g);
        } finally {
            g.dispose();
        }
        return image;
    }
}
