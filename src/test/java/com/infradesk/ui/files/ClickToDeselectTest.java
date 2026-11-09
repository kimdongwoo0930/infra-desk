package com.infradesk.ui.files;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickToDeselectTest {

    /** 평범한 왼쪽 클릭: 수정 키 없음, 우클릭 메뉴 아님. */
    private static void plainPress(ClickToDeselect d, int row, boolean selected, int selectedCount) {
        d.pressed(row, selected, selectedCount, true, false, false);
    }

    @Test
    void 하나만_선택된_행을_다시_누르면_풀린다() {
        ClickToDeselect d = new ClickToDeselect();
        plainPress(d, 3, true, 1);

        assertTrue(d.shouldClear(3, 1));
    }

    @Test
    void 선택돼_있지_않던_행을_누르면_풀지_않는다() {
        ClickToDeselect d = new ClickToDeselect();
        plainPress(d, 3, false, 1);   // 다른 행이 선택돼 있었고, 이번에 처음 누른다

        assertFalse(d.shouldClear(3, 1));
    }

    @Test
    void 여러_개가_선택돼_있던_경우에는_풀지_않는다() {
        ClickToDeselect d = new ClickToDeselect();
        plainPress(d, 3, true, 2);   // 눌러서 이 행만 남기는 동작이다

        assertFalse(d.shouldClear(3, 1));
    }

    @Test
    void Shift_Ctrl_Cmd를_누른_클릭은_풀지_않는다() {
        ClickToDeselect d = new ClickToDeselect();
        d.pressed(3, true, 1, true, true, false);

        assertFalse(d.shouldClear(3, 1), "여러 개 고르기 동작이라 건드리지 않는다");
    }

    @Test
    void 오른쪽_클릭과_우클릭_메뉴_누르기는_풀지_않는다() {
        ClickToDeselect d = new ClickToDeselect();
        d.pressed(3, true, 1, false, false, false);
        assertFalse(d.shouldClear(3, 1));

        d.pressed(3, true, 1, true, false, true);   // macOS의 Ctrl+클릭
        assertFalse(d.shouldClear(3, 1));
    }

    @Test
    void 더블클릭은_풀지_않는다() {
        ClickToDeselect d = new ClickToDeselect();
        plainPress(d, 3, true, 1);

        assertFalse(d.shouldClear(3, 2), "더블클릭은 열기다");
    }

    @Test
    void 빈_곳을_누르면_풀지_않는다() {
        ClickToDeselect d = new ClickToDeselect();
        plainPress(d, -1, false, 1);

        assertFalse(d.shouldClear(-1, 1));
    }

    @Test
    void 누른_행과_뗀_행이_다르면_풀지_않는다() {
        ClickToDeselect d = new ClickToDeselect();
        plainPress(d, 3, true, 1);

        assertFalse(d.shouldClear(4, 1));
    }

    @Test
    void 한_번_물어본_뒤에는_다시_풀지_않는다() {
        ClickToDeselect d = new ClickToDeselect();
        plainPress(d, 3, true, 1);
        assertTrue(d.shouldClear(3, 1));

        assertFalse(d.shouldClear(3, 1), "눌러서 기록한 것은 한 번만 쓴다");
    }
}
