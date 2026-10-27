package com.infradesk.ui.components;

import com.infradesk.ui.Theme;

import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractButton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TabBarTest {

    /** 개요 / 모니터링 / 컨테이너(4) / 로그(준비 중) */
    private static TabBar bar() {
        TabBar bar = new TabBar();
        bar.addTab("overview", "개요");
        bar.addTab("monitoring", "모니터링");
        bar.addTab("containers", "컨테이너");
        bar.addTab("logs", "로그");
        bar.setCount("containers", 4);
        bar.setComingSoon("logs");
        return bar;
    }

    private static AbstractButton tab(TabBar bar, int index) {
        return (AbstractButton) bar.getComponent(index);
    }

    @Test
    void 처음에는_첫_탭이_선택된다() {
        assertEquals("overview", bar().selectedId());
    }

    @Test
    void 다른_탭을_누르면_선택이_바뀌고_id를_알린다() {
        TabBar bar = bar();
        List<String> heard = new ArrayList<>();
        bar.onSelect(heard::add);

        tab(bar, 1).doClick();

        assertEquals("monitoring", bar.selectedId());
        assertEquals(List.of("monitoring"), heard);
    }

    @Test
    void 이미_선택된_탭을_다시_눌러도_알리지_않는다() {
        TabBar bar = bar();
        List<String> heard = new ArrayList<>();
        bar.onSelect(heard::add);

        tab(bar, 0).doClick();

        assertEquals(List.of(), heard);
    }

    @Test
    void 준비_중_탭은_눌러도_선택되지_않는다() {
        TabBar bar = bar();
        List<String> heard = new ArrayList<>();
        bar.onSelect(heard::add);

        tab(bar, 3).doClick();

        assertEquals("overview", bar.selectedId());
        assertEquals(List.of(), heard);
        assertFalse(tab(bar, 3).isEnabled());
        assertEquals(ComingSoon.TOOLTIP, ((javax.swing.JComponent) bar.getComponent(3)).getToolTipText());
    }

    @Test
    void 선택된_탭_아래에는_파랑_밑줄이_다른_탭_아래에는_회색_선이_그려진다() {
        TabBar bar = bar();
        BufferedImage img = Pixels.paint(bar);
        int bottom = img.getHeight() - 1;

        Rectangle selected = bar.getComponent(0).getBounds();
        Rectangle other = bar.getComponent(1).getBounds();
        assertEquals(Theme.ACCENT_LINE.getRGB(), img.getRGB(selected.x + selected.width / 2, bottom));
        assertEquals(Theme.DIVIDER.getRGB(), img.getRGB(other.x + other.width / 2, bottom));
    }

    @Test
    void 개수_배지나_준비_중_글자가_있으면_탭이_넓어진다() {
        TabBar plain = new TabBar();
        plain.addTab("containers", "컨테이너");
        int before = plain.getComponent(0).getPreferredSize().width;

        plain.setCount("containers", 4);
        assertTrue(plain.getComponent(0).getPreferredSize().width > before);

        TabBar soon = new TabBar();
        soon.addTab("logs", "로그");
        int logsBefore = soon.getComponent(0).getPreferredSize().width;
        soon.setComingSoon("logs");
        assertTrue(soon.getComponent(0).getPreferredSize().width > logsBefore);
    }

    @Test
    void 없는_탭_id는_예외다() {
        assertThrows(IllegalArgumentException.class, () -> bar().setCount("nope", 1));
    }
}
