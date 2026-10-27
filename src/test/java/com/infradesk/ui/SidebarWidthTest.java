package com.infradesk.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SidebarWidthTest {

    @Test
    void 너무_좁게_끌면_닫는다() {
        assertTrue(SidebarWidth.shouldCollapse(89));
        assertTrue(SidebarWidth.shouldCollapse(1));
        assertFalse(SidebarWidth.shouldCollapse(90), "90까지는 이름만 보이는 폭이다");
        assertFalse(SidebarWidth.shouldCollapse(260));
    }

    @Test
    void 폭이_0_이하이면_닫을지_세지_않는다() {
        // 0은 이미 닫힌 상태이거나 아직 배치 전이고, -1은 Swing의 "아직 정해지지 않음"이다.
        assertFalse(SidebarWidth.shouldCollapse(0));
        assertFalse(SidebarWidth.shouldCollapse(-1));
    }

    @Test
    void 이백_미만이면_이름만_보인다() {
        assertTrue(SidebarWidth.isCompact(199));
        assertTrue(SidebarWidth.isCompact(90));
        assertFalse(SidebarWidth.isCompact(200));
        assertFalse(SidebarWidth.isCompact(SidebarWidth.DEFAULT));
    }

    @Test
    void 기본_폭은_이름만_보이는_폭보다_넓다() {
        // 앱을 처음 켰을 때는 전체 모양이어야 한다.
        assertFalse(SidebarWidth.isCompact(SidebarWidth.DEFAULT));
        assertFalse(SidebarWidth.shouldCollapse(SidebarWidth.DEFAULT));
    }

    @Test
    void 최대_폭을_넘지_않게_맞춘다() {
        assertEquals(420, SidebarWidth.clamp(500));
        assertEquals(420, SidebarWidth.clamp(420));
        assertEquals(300, SidebarWidth.clamp(300));
    }

    @Test
    void 다시_펼_때는_기억한_폭으로_돌아가고_너무_좁았으면_기본_폭이다() {
        assertEquals(300, SidebarWidth.restoreWidth(300));
        assertEquals(140, SidebarWidth.restoreWidth(140));
        assertEquals(SidebarWidth.DEFAULT, SidebarWidth.restoreWidth(139), "너무 좁은 폭으로 돌아가면 어색하다");
        assertEquals(SidebarWidth.DEFAULT, SidebarWidth.restoreWidth(0));
        assertEquals(420, SidebarWidth.restoreWidth(900));
    }
}
