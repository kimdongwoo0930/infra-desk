package com.infradesk.ui.overview;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;

/**
 * 개요 탭의 두 열 배치 (디자인 v2): 담은 컴포넌트 둘 중 첫째가 왼쪽(남는 폭), 둘째가 오른쪽(고정 폭 300)이다.
 *
 * <p>창이 좁아서 왼쪽에 최소 폭({@value #LEFT_MIN})을 못 주면 오른쪽 열을 왼쪽 아래로 내려서 한 열로 쌓는다
 * (4칸 카드가 너무 좁아지는 것을 막는다). 어느 쪽으로 놓을지는 담는 패널의 지금 폭으로 정한다.
 */
public final class TwoColumnLayout implements LayoutManager {

    /** 오른쪽 열의 폭. */
    public static final int RIGHT_WIDTH = 300;
    static final int GAP = 16;
    /** 왼쪽 열이 이보다 좁아지면 한 열로 쌓는다. */
    static final int LEFT_MIN = 560;

    /** 담는 폭이 이만큼 이상이면 두 열로 나란히 놓는다. */
    static boolean sideBySide(int width) {
        return width <= 0 || width >= LEFT_MIN + GAP + RIGHT_WIDTH;
    }

    @Override
    public void addLayoutComponent(String name, Component comp) {
    }

    @Override
    public void removeLayoutComponent(Component comp) {
    }

    @Override
    public Dimension preferredLayoutSize(Container parent) {
        synchronized (parent.getTreeLock()) {
            Insets in = parent.getInsets();
            int width = availableWidth(parent) - in.left - in.right;
            int leftHeight = parent.getComponent(0).getPreferredSize().height;
            Component right = parent.getComponent(1);
            int rightHeight = right.isVisible() ? right.getPreferredSize().height : 0;
            int height = sideBySide(width) ? Math.max(leftHeight, rightHeight)
                    : leftHeight + (rightHeight > 0 ? GAP + rightHeight : 0);
            return new Dimension(in.left + in.right + LEFT_MIN + GAP + RIGHT_WIDTH, in.top + in.bottom + height);
        }
    }

    /**
     * 지금 쓸 수 있는 폭. 스크롤 창 안에서는 뷰포트의 폭을 쓴다: 스크롤 창은 담은 패널의 높이를 먼저 묻고 그 뒤에
     * 폭을 맞추는데, 그때 패널의 폭은 아직 이전 값이라 그 폭으로 높이를 계산하면 한 열로 쌓일 때 아래가 잘린다.
     */
    private static int availableWidth(Container parent) {
        return parent.getParent() instanceof javax.swing.JViewport viewport ? viewport.getWidth() : parent.getWidth();
    }

    @Override
    public Dimension minimumLayoutSize(Container parent) {
        Insets in = parent.getInsets();
        return new Dimension(in.left + in.right + 200, in.top + in.bottom);
    }

    @Override
    public void layoutContainer(Container parent) {
        synchronized (parent.getTreeLock()) {
            Insets in = parent.getInsets();
            int x = in.left;
            int y = in.top;
            int width = parent.getWidth() - in.left - in.right;
            Component left = parent.getComponent(0);
            Component right = parent.getComponent(1);
            int leftHeight = left.getPreferredSize().height;
            int rightHeight = right.isVisible() ? right.getPreferredSize().height : 0;
            if (sideBySide(width)) {
                int leftWidth = width - RIGHT_WIDTH - GAP;
                left.setBounds(x, y, leftWidth, leftHeight);
                right.setBounds(x + leftWidth + GAP, y, RIGHT_WIDTH, rightHeight);
            } else {
                left.setBounds(x, y, width, leftHeight);
                right.setBounds(x, y + leftHeight + GAP, width, rightHeight);
            }
        }
    }
}
