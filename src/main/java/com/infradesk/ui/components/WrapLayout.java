package com.infradesk.ui.components;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/**
 * 폭이 모자라면 다음 줄로 넘어가는 {@link FlowLayout}. 칩 여러 개를 늘어놓을 때 쓴다.
 *
 * <p>기본 FlowLayout은 한 줄로만 계산한 높이를 알려 주어서, 줄이 넘어가도 담는 패널의 높이가 늘지 않아 아래가
 * 잘린다. 이 레이아웃은 지금 폭에서 줄이 몇 개가 되는지까지 계산해서 알려 준다.
 */
public class WrapLayout extends FlowLayout {

    /** true면 맨 앞·맨 위·맨 뒤에는 간격을 두지 않는다(칩 사이에만 간격). FlowLayout은 가장자리에도 간격을 둔다. */
    private final boolean edgeless;

    public WrapLayout(int align, int hgap, int vgap) {
        this(align, hgap, vgap, false);
    }

    public WrapLayout(int align, int hgap, int vgap, boolean edgeless) {
        super(align, hgap, vgap);
        this.edgeless = edgeless;
    }

    /** 마지막으로 다시 계산을 요청한 높이. 같은 높이로 계속 요청하며 맴돌지 않게 한다. */
    private int requestedHeight = -1;

    @Override
    public void layoutContainer(Container target) {
        super.layoutContainer(target);
        growIfClipped(target);
        if (edgeless) {
            // FlowLayout이 가장자리에 둔 간격만큼 되돌린다.
            for (int i = 0; i < target.getComponentCount(); i++) {
                Component c = target.getComponent(i);
                if (c.isVisible()) {
                    c.setLocation(c.getX() - getHgap(), c.getY() - getVgap());
                }
            }
        }
    }

    /**
     * 칩이 줄을 넘겼는데 담는 쪽이 한 줄 높이만 주어서 아래가 잘렸으면, 지금 폭으로 다시 높이를 계산하게 한다.
     * 첫 배치 때는 담는 패널의 폭이 아직 정해지기 전이라 높이를 한 줄 기준으로 계산하는 일이 있고, 폭이 정해진 뒤에는
     * 아무도 다시 묻지 않아서 잘린 채로 남는다.
     */
    private void growIfClipped(Container target) {
        int needed = preferredLayoutSize(target).height;
        if (needed > target.getHeight() && needed != requestedHeight) {
            requestedHeight = needed;
            javax.swing.SwingUtilities.invokeLater(target::revalidate);
        } else if (needed <= target.getHeight()) {
            requestedHeight = -1;
        }
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return layoutSize(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        Dimension minimum = layoutSize(target, false);
        minimum.width -= getHgap() + 1;
        return minimum;
    }

    private Dimension layoutSize(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            // 아직 배치 전이라 폭이 0이면 폭이 있는 가장 가까운 윗단계의 폭을 쓴다.
            Container container = target;
            while (container.getSize().width == 0 && container.getParent() != null) {
                container = container.getParent();
            }
            int targetWidth = container.getSize().width;
            if (targetWidth == 0) {
                targetWidth = Integer.MAX_VALUE;
            }
            int hgap = getHgap();
            int vgap = getVgap();
            Insets insets = target.getInsets();
            int horizontalInsetsAndGap = insets.left + insets.right + hgap * 2;
            int maxWidth = targetWidth - horizontalInsetsAndGap;

            Dimension dim = new Dimension(0, 0);
            int rowWidth = 0;
            int rowHeight = 0;
            for (int i = 0; i < target.getComponentCount(); i++) {
                Component c = target.getComponent(i);
                if (!c.isVisible()) {
                    continue;
                }
                Dimension d = preferred ? c.getPreferredSize() : c.getMinimumSize();
                if (rowWidth + d.width > maxWidth && rowWidth > 0) {
                    addRow(dim, rowWidth, rowHeight);
                    rowWidth = 0;
                    rowHeight = 0;
                }
                if (rowWidth != 0) {
                    rowWidth += hgap;
                }
                rowWidth += d.width;
                rowHeight = Math.max(rowHeight, d.height);
            }
            addRow(dim, rowWidth, rowHeight);
            dim.width += edgeless ? insets.left + insets.right : horizontalInsetsAndGap;
            dim.height += insets.top + insets.bottom + (edgeless ? 0 : vgap * 2);
            // 스크롤 창 안에서는 가로 스크롤이 생기지 않게 한 칸 줄인다.
            Container scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane.class, target);
            if (scrollPane != null && target.isValid()) {
                dim.width -= hgap + 1;
            }
            return dim;
        }
    }

    private void addRow(Dimension dim, int rowWidth, int rowHeight) {
        dim.width = Math.max(dim.width, rowWidth);
        if (dim.height > 0) {
            dim.height += getVgap();
        }
        dim.height += rowHeight;
    }
}
