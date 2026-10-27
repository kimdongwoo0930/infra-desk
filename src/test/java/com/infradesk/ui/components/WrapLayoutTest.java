package com.infradesk.ui.components;

import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.awt.FlowLayout;
import javax.swing.JPanel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WrapLayoutTest {

    private static JPanel chips(int count, boolean edgeless) {
        JPanel p = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 6, edgeless));
        for (int i = 0; i < count; i++) {
            JPanel chip = new JPanel();
            chip.setPreferredSize(new Dimension(50, 20));
            p.add(chip);
        }
        return p;
    }

    @Test
    void chipsThatDoNotFitMoveToTheNextLineAndTheHeightGrows() {
        JPanel p = chips(6, true);
        p.setSize(150, 0);

        // 한 줄에 둘(50+6+50=106, 셋째는 162라 넘친다): 6개 → 3줄 = 3*20 + 2*6
        assertEquals(3 * 20 + 2 * 6, p.getPreferredSize().height);
    }

    @Test
    void aWideContainerKeepsEverythingOnOneLine() {
        JPanel p = chips(6, true);
        p.setSize(1000, 0);

        assertEquals(20, p.getPreferredSize().height);
    }

    @Test
    void edgelessLayoutPutsTheFirstChipAtTheTopLeftCorner() {
        JPanel p = chips(3, true);
        p.setSize(1000, 100);

        p.doLayout();

        assertEquals(0, p.getComponent(0).getX());
        assertEquals(0, p.getComponent(0).getY());
        assertEquals(56, p.getComponent(1).getX(), "칩 사이에만 간격(6)");
    }

    @Test
    void theNormalLayoutKeepsTheGapAtTheEdges() {
        JPanel p = chips(2, false);
        p.setSize(1000, 100);

        p.doLayout();

        assertTrue(p.getComponent(0).getX() >= 6);
    }
}
