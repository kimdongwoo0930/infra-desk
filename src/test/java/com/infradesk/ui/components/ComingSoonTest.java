package com.infradesk.ui.components;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ComingSoonTest {

    @Test
    void 안에_들어_있는_자식까지_비활성이_되고_안내가_붙는다() {
        JPanel outer = new JPanel();
        JButton button = new JButton("로그");
        JLabel label = new JLabel("설명");
        JPanel inner = new JPanel();
        JCheckBox box = new JCheckBox("켜기");
        inner.add(box);
        outer.add(button);
        outer.add(label);
        outer.add(inner);

        ComingSoon.apply(outer);

        for (javax.swing.JComponent c : new javax.swing.JComponent[] {outer, button, label, inner, box}) {
            assertFalse(c.isEnabled(), c.getClass().getSimpleName() + "도 비활성이어야 한다");
            assertEquals(ComingSoon.TOOLTIP, c.getToolTipText());
        }
    }

    @Test
    void 준비_중_버튼은_눌러도_아무_일도_하지_않는다() {
        AtomicInteger clicks = new AtomicInteger();
        JButton button = new JButton("읽기 전용");
        button.addActionListener(e -> clicks.incrementAndGet());

        ComingSoon.apply(button).doClick();

        assertEquals(0, clicks.get());
    }

    @Test
    void 켤_수_없는_스위치는_눌러도_켜지지_않는다() {
        // 안전과 관련된 스위치가 "켜진 것처럼" 보이면 위험하므로, 준비 중이면 상태가 바뀌지 않아야 한다.
        ToggleSwitch readOnly = ComingSoon.apply(new ToggleSwitch());

        readOnly.doClick();

        assertFalse(readOnly.isSelected());
    }

    @Test
    void 배지는_준비_중_글자의_중립_종류다() {
        StatusBadge badge = ComingSoon.badge();
        assertEquals("준비 중", badge.getText());
        assertEquals(StatusBadge.Kind.NEUTRAL, badge.kind());
    }
}
