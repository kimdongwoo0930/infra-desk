package com.infradesk.ui.components;

import com.infradesk.ui.Theme;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractButton;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SegmentedControlTest {

    private static SegmentedControl control() {
        return new SegmentedControl(List.of("실시간", "1시간", "24시간", "7일"));
    }

    private static AbstractButton segment(SegmentedControl c, int index) {
        return (AbstractButton) c.getComponent(index);
    }

    @Test
    void 처음에는_첫_번째가_선택된다() {
        assertEquals(0, control().getSelectedIndex());
    }

    @Test
    void 다른_버튼을_누르면_하나만_선택되고_리스너가_번호를_받는다() {
        SegmentedControl c = control();
        List<Integer> heard = new ArrayList<>();
        c.addSelectionListener(heard::add);

        segment(c, 2).doClick();

        assertEquals(2, c.getSelectedIndex());
        assertEquals(List.of(2), heard);
        // 이전 선택은 풀렸다.
        assertEquals(false, segment(c, 0).isSelected());
    }

    @Test
    void 이미_선택된_버튼을_다시_눌러도_알리지_않는다() {
        SegmentedControl c = control();
        List<Integer> heard = new ArrayList<>();
        c.addSelectionListener(heard::add);

        segment(c, 0).doClick();

        assertEquals(List.of(), heard);
        assertEquals(0, c.getSelectedIndex());
    }

    @Test
    void 코드로_선택을_바꿀_수_있다() {
        SegmentedControl c = control();
        c.setSelectedIndex(3);
        assertEquals(3, c.getSelectedIndex());
    }

    @Test
    void 선택된_버튼은_파랑으로_칠해진다() {
        BufferedImage img = Pixels.paint(control());
        // 바깥 여백 3 + 버튼 안쪽(x=3) → x=6, 세로 가운데(y=16): 글자가 없는 왼쪽 여백이다.
        assertEquals(Theme.ACCENT.getRGB(), img.getRGB(6, 16));
    }
}
