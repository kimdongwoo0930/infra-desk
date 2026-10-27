package com.infradesk.ui.fleet;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;

/**
 * 같은 폭의 칸을 폭에 맞는 만큼 늘어놓는 격자 (디자인 v2의 {@code repeat(auto-fill, minmax(최소, 1fr))}). 칸 수는
 * "최소 폭 + 간격"이 몇 번 들어가는지로 정하고, 남는 폭은 칸이 나누어 가진다. 한 줄의 칸은 그 줄에서 가장 큰 높이를 같이 쓴다.
 *
 * <p>스크롤 창 안에서는 뷰포트의 폭으로 칸 수를 정한다. 스크롤 창은 담은 패널의 높이를 먼저 묻고 그 뒤에 폭을 맞추므로,
 * 패널의 폭이 아직 이전 값일 때 계산하면 아래가 잘린다({@code TwoColumnLayout}도 같은 이유로 그렇게 한다).
 */
final class CardGridLayout implements LayoutManager {

    private final int minWidth;
    private final int gap;
    /** true면 칸이 줄 수보다 많은 열을 만들지 않는다(디자인의 auto-fit: 칸이 적으면 그 칸들이 폭을 나누어 가진다). */
    private final boolean fit;

    CardGridLayout(int minWidth, int gap) {
        this(minWidth, gap, false);
    }

    CardGridLayout(int minWidth, int gap, boolean fit) {
        this.minWidth = minWidth;
        this.gap = gap;
        this.fit = fit;
    }

    private int columnsFor(Container parent, int innerWidth) {
        int cols = columns(innerWidth, minWidth, gap);
        return fit ? Math.max(1, Math.min(cols, parent.getComponentCount())) : cols;
    }

    /** 폭 안에 들어가는 칸 수(최소 1). */
    static int columns(int width, int minWidth, int gap) {
        return Math.max(1, (width + gap) / (minWidth + gap));
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
            int inner = availableWidth(parent) - in.left - in.right;
            int cols = columnsFor(parent, inner);
            return new Dimension(in.left + in.right + minWidth, in.top + in.bottom + totalHeight(parent, cols));
        }
    }

    @Override
    public Dimension minimumLayoutSize(Container parent) {
        Insets in = parent.getInsets();
        return new Dimension(in.left + in.right + minWidth, in.top + in.bottom);
    }

    @Override
    public void layoutContainer(Container parent) {
        synchronized (parent.getTreeLock()) {
            Insets in = parent.getInsets();
            int inner = parent.getWidth() - in.left - in.right;
            int cols = columnsFor(parent, inner);
            int cellWidth = (inner - gap * (cols - 1)) / cols;
            int y = in.top;
            int n = parent.getComponentCount();
            for (int start = 0; start < n; start += cols) {
                int rowHeight = rowHeight(parent, start, cols, cellWidth);
                for (int i = start; i < Math.min(n, start + cols); i++) {
                    parent.getComponent(i).setBounds(in.left + (i - start) * (cellWidth + gap), y, cellWidth, rowHeight);
                }
                y += rowHeight + gap;
            }
        }
    }

    private int totalHeight(Container parent, int cols) {
        Insets in = parent.getInsets();
        int inner = availableWidth(parent) - in.left - in.right;
        int cellWidth = (inner - gap * (cols - 1)) / cols;
        int n = parent.getComponentCount();
        int height = 0;
        for (int start = 0; start < n; start += cols) {
            height += rowHeight(parent, start, cols, cellWidth) + (start > 0 ? gap : 0);
        }
        return height;
    }

    /** 한 줄에서 가장 큰 높이. 그 칸 폭에서 글자가 줄바꿈되는 것까지 반영하려고 먼저 칸 폭으로 배치해 본다. */
    private int rowHeight(Container parent, int start, int cols, int cellWidth) {
        int height = 0;
        for (int i = start; i < Math.min(parent.getComponentCount(), start + cols); i++) {
            Component c = parent.getComponent(i);
            if (c.getWidth() != cellWidth) {
                c.setSize(cellWidth, Math.max(1, c.getHeight()));
                c.doLayout();
            }
            height = Math.max(height, c.getPreferredSize().height);
        }
        return height;
    }

    /**
     * 이 패널이 쓸 수 있는 폭. 스크롤 창 안이면 뷰포트의 폭에서 그 사이 부모들의 여백을 뺀 값이다(패널은 바로 뷰포트의
     * 자식이 아니고, 여백이 있는 부모 안에 있을 수 있다).
     */
    private static int availableWidth(Container parent) {
        int width = parent.getWidth();
        for (Container c = parent.getParent(); c != null; c = c.getParent()) {
            if (c instanceof javax.swing.JViewport viewport) {
                width = viewport.getWidth();
                for (Container between = parent.getParent(); between != null && between != viewport;
                     between = between.getParent()) {
                    Insets i = between.getInsets();
                    width -= i.left + i.right;
                }
                break;
            }
        }
        return width;
    }
}
