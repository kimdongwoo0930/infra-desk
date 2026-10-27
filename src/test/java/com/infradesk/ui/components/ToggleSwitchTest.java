package com.infradesk.ui.components;

import com.infradesk.ui.Theme;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToggleSwitchTest {

    @Test
    void 누를_때마다_켜지고_꺼진다() {
        ToggleSwitch sw = new ToggleSwitch();
        assertFalse(sw.isSelected());
        sw.doClick();
        assertTrue(sw.isSelected());
        sw.doClick();
        assertFalse(sw.isSelected());
    }

    @Test
    void 꺼짐이면_회색_트랙에_손잡이는_왼쪽이다() {
        BufferedImage img = Pixels.paint(new ToggleSwitch(false));
        // 오른쪽(x=30)은 손잡이(3~17)가 없는 트랙이다.
        assertEquals(Theme.SECONDARY_BUTTON_BORDER.getRGB(), img.getRGB(30, 10));
        // 손잡이 한가운데(x=10)는 끔 손잡이색이다.
        assertEquals(Theme.TEXT.getRGB(), img.getRGB(10, 10));
    }

    @Test
    void 켜짐이면_파랑_트랙에_손잡이는_오른쪽이다() {
        BufferedImage img = Pixels.paint(new ToggleSwitch(true));
        // 왼쪽(x=6)은 손잡이(17~31)가 없는 트랙이다.
        assertEquals(Theme.ACCENT.getRGB(), img.getRGB(6, 10));
        // 손잡이 한가운데(x=24)는 흰색이다.
        assertEquals(0xFFFFFFFF, img.getRGB(24, 10));
    }

    @Test
    void 비활성이면_반투명하게_그려진다() {
        ToggleSwitch sw = new ToggleSwitch(false);
        sw.setEnabled(false);
        int pixel = Pixels.paint(sw).getRGB(30, 10);
        assertTrue(Pixels.alpha(pixel) < 255, "비활성은 반투명이어야 한다");
        assertTrue(Pixels.alpha(pixel) > 0);
    }
}
