package com.infradesk.ui.components;

import com.infradesk.ui.Theme;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilterChipTest {

    @Test
    void 선택되면_파랑_테두리와_옅은_파랑_배경이다() {
        FilterChip chip = new FilterChip("전체");
        chip.setSelected(true);
        BufferedImage img = Pixels.paint(chip);
        // 맨 윗줄 가운데는 테두리 한 줄이다.
        assertEquals(Theme.ACCENT_LINE.getRGB(), img.getRGB(img.getWidth() / 2, 0));
        // 왼쪽 안쪽 여백(글자 없음)은 배경색이다.
        assertEquals(Theme.ACCENT_SOFT_BG.getRGB(), img.getRGB(6, 14));
    }

    @Test
    void 선택되지_않으면_회색_테두리에_배경이_투명하다() {
        BufferedImage img = Pixels.paint(new FilterChip("전체"));
        assertEquals(Theme.INPUT_BORDER.getRGB(), img.getRGB(img.getWidth() / 2, 0));
        assertEquals(0, Pixels.alpha(img.getRGB(6, 14)));
    }

    @Test
    void 개수를_붙이면_칩이_넓어진다() {
        FilterChip chip = new FilterChip("실행 중");
        int without = chip.getPreferredSize().width;
        chip.setCount(4);
        assertTrue(chip.getPreferredSize().width > without);
        assertEquals(28, chip.getPreferredSize().height);
    }
}
