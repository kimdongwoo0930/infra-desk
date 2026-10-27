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
    void 상태_점은_실행_중이면_초록으로_채우고_정지면_속이_빈다() {
        FilterChip running = new FilterChip("web");
        running.setStatusDot(true);
        // 점은 왼쪽 여백(10) 안쪽에서 시작한다: x=10~18, 세로 가운데(14).
        assertEquals(Theme.RUNNING_DOT.getRGB(), Pixels.paint(running).getRGB(14, 14));

        FilterChip stopped = new FilterChip("web");
        stopped.setStatusDot(false);
        // 정지 점은 가운데가 비어 있다(칩 배경이 투명이라 알파 0).
        assertEquals(0, Pixels.alpha(Pixels.paint(stopped).getRGB(14, 14)));
    }

    @Test
    void 상태_점이_있으면_칩이_점만큼_넓어진다() {
        FilterChip chip = new FilterChip("web");
        int without = chip.getPreferredSize().width;
        chip.setStatusDot(true);
        assertEquals(without + 8 + 6, chip.getPreferredSize().width);
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
