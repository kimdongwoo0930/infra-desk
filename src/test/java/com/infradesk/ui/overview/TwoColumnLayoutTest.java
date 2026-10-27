package com.infradesk.ui.overview;

import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwoColumnLayoutTest {

    private static JPanel box(int height) {
        JPanel p = new JPanel();
        p.setPreferredSize(new Dimension(100, height));
        return p;
    }

    private static JPanel container(JPanel left, JPanel right) {
        JPanel parent = new JPanel(new TwoColumnLayout());
        parent.add(left);
        parent.add(right);
        return parent;
    }

    @Test
    void wideWindowPutsTheColumnsSideBySide() {
        assertTrue(TwoColumnLayout.sideBySide(TwoColumnLayout.LEFT_MIN + TwoColumnLayout.GAP + TwoColumnLayout.RIGHT_WIDTH));
        assertTrue(TwoColumnLayout.sideBySide(0), "폭을 아직 모르면 나란히로 시작한다");
    }

    @Test
    void narrowWindowStacksTheRightColumnUnderTheLeft() {
        assertFalse(TwoColumnLayout.sideBySide(TwoColumnLayout.LEFT_MIN + TwoColumnLayout.GAP + TwoColumnLayout.RIGHT_WIDTH - 1));
    }

    @Test
    void sideBySideLayoutGivesTheRightColumnItsFixedWidthAndTheRestToTheLeft() {
        JPanel left = box(300);
        JPanel right = box(500);
        JPanel parent = container(left, right);
        parent.setSize(1000, 800);

        parent.doLayout();

        assertEquals(TwoColumnLayout.RIGHT_WIDTH, right.getWidth());
        assertEquals(1000 - TwoColumnLayout.RIGHT_WIDTH - TwoColumnLayout.GAP, left.getWidth());
        assertEquals(0, right.getY());
        assertEquals(500, parent.getPreferredSize().height, "나란히면 더 높은 열의 높이");
    }

    @Test
    void stackedLayoutPlacesTheRightColumnBelowAndAddsBothHeights() {
        JPanel left = box(300);
        JPanel right = box(500);
        JPanel parent = container(left, right);
        parent.setSize(700, 800);

        parent.doLayout();

        assertEquals(700, left.getWidth());
        assertEquals(700, right.getWidth());
        assertEquals(300 + TwoColumnLayout.GAP, right.getY());
        assertEquals(300 + TwoColumnLayout.GAP + 500, parent.getPreferredSize().height);
    }

    @Test
    void heightIsMeasuredWithTheViewportWidthInsideAScrollPane() {
        JPanel left = box(300);
        JPanel right = box(500);
        JPanel parent = container(left, right);
        JScrollPane scroll = new JScrollPane(parent);
        scroll.setSize(700, 400);
        scroll.doLayout();
        scroll.getViewport().setSize(700, 400);

        // 패널 자신의 폭이 아직 0이어도 뷰포트가 좁으면 쌓인 높이로 계산한다.
        assertEquals(300 + TwoColumnLayout.GAP + 500, parent.getPreferredSize().height);
    }

    @Test
    void invisibleRightColumnTakesNoHeightWhenStacked() {
        JPanel left = box(300);
        JPanel right = box(500);
        right.setVisible(false);
        JPanel parent = container(left, right);
        parent.setSize(700, 800);

        assertEquals(300, parent.getPreferredSize().height);
    }
}
