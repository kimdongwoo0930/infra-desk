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
    void 버튼_글자를_나중에_바꿀_수_있고_폭이_따라_바뀐다() {
        SegmentedControl c = control();
        int before = c.getComponent(1).getPreferredSize().width;

        c.setLabel(1, "1시간 (최근 60개)");

        assertEquals("1시간 (최근 60개)", segment(c, 1).getText());
        assertEquals(true, c.getComponent(1).getPreferredSize().width > before);
    }

    @Test
    void 칸_앞에_점을_붙이면_그_칸이_넓어지고_점_색이_그려진다() {
        SegmentedControl c = control();
        int before = c.getComponent(0).getPreferredSize().width;

        c.setDot(0, Theme.RUNNING_DOT);

        assertEquals(before + 6 + 6, c.getComponent(0).getPreferredSize().width);
        // 첫 칸은 선택 상태라 파란 배경 위에 초록 점이 그려진다: 점은 칸 가운데 덩어리의 왼쪽에 있다.
        BufferedImage img = Pixels.paint(c);
        java.awt.Rectangle first = c.getComponent(0).getBounds();
        int contentWidth = c.getComponent(0).getPreferredSize().width - 24;   // 좌우 여백 12씩
        int dotX = first.x + (first.width - contentWidth) / 2 + 3;            // 점의 가운데
        assertEquals(Theme.RUNNING_DOT.getRGB(), img.getRGB(dotX, first.y + first.height / 2));
    }

    @Test
    void 칸을_비활성으로_하면_눌러도_선택되지_않고_안내가_붙는다() {
        SegmentedControl c = control();
        List<Integer> heard = new ArrayList<>();
        c.addSelectionListener(heard::add);

        c.setSegmentEnabled(2, false, "이 기간은 없어요");
        segment(c, 2).doClick();

        assertEquals(0, c.getSelectedIndex());
        assertEquals(List.of(), heard);
        assertEquals(false, segment(c, 2).isEnabled());
        assertEquals("이 기간은 없어요", ((javax.swing.JComponent) c.getComponent(2)).getToolTipText());

        c.setSegmentEnabled(2, true, null);
        segment(c, 2).doClick();
        assertEquals(2, c.getSelectedIndex());
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
