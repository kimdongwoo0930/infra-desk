package com.infradesk.ui.settings;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.ui.Theme;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 설정 화면의 항목 하나(일반, 알림 …)의 틀 (디자인 v2): 큰 제목, 흐린 부제, 그 아래에 카드들이 세로로 쌓인다.
 * 각 항목은 이 클래스를 상속해 {@link #addCard}로 카드를 붙인다.
 */
public abstract class SettingsPage extends JPanel implements javax.swing.Scrollable {

    /** 카드 열의 최대 폭. 넓은 창에서도 한 줄이 너무 길어지지 않게 한다. */
    static final int MAX_WIDTH = 980;

    private final String id;
    private final String menuLabel;
    private final JPanel column = new JPanel();

    protected SettingsPage(String id, String menuLabel, String title, String subtitle) {
        super(new BorderLayout());
        this.id = id;
        this.menuLabel = menuLabel;
        setOpaque(false);

        JLabel heading = new JLabel(title);
        heading.setFont(heading.getFont().deriveFont(java.awt.Font.BOLD, 24f));
        JLabel sub = new JLabel(subtitle);
        sub.setForeground(Theme.TEXT_MUTED);
        sub.putClientProperty(FlatClientProperties.STYLE, "font: +0");
        heading.setAlignmentX(LEFT_ALIGNMENT);
        sub.setAlignmentX(LEFT_ALIGNMENT);

        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.add(heading);
        column.add(Box.createVerticalStrut(6));
        column.add(sub);
        column.add(Box.createVerticalStrut(24));

        // 카드 열은 왼쪽에 붙이고, 창이 넓어도 최대 폭을 넘지 않는다.
        JPanel wrap = new JPanel(new CappedWidthLayout());
        wrap.setOpaque(false);
        wrap.setBorder(BorderFactory.createEmptyBorder(36, 44, 40, 44));
        wrap.add(column);
        add(wrap, BorderLayout.NORTH);
    }

    /** 담긴 하나를 왼쪽 위에 놓고, 폭은 쓸 수 있는 만큼(최대 {@value #MAX_WIDTH})으로 한다. */
    private static final class CappedWidthLayout implements java.awt.LayoutManager {
        @Override
        public void addLayoutComponent(String name, Component comp) {
        }

        @Override
        public void removeLayoutComponent(Component comp) {
        }

        @Override
        public Dimension preferredLayoutSize(java.awt.Container parent) {
            java.awt.Insets in = parent.getInsets();
            Dimension d = parent.getComponent(0).getPreferredSize();
            return new Dimension(Math.min(MAX_WIDTH, d.width) + in.left + in.right, d.height + in.top + in.bottom);
        }

        @Override
        public Dimension minimumLayoutSize(java.awt.Container parent) {
            java.awt.Insets in = parent.getInsets();
            return new Dimension(in.left + in.right + 300, in.top + in.bottom);
        }

        @Override
        public void layoutContainer(java.awt.Container parent) {
            java.awt.Insets in = parent.getInsets();
            Component child = parent.getComponent(0);
            int width = Math.min(MAX_WIDTH, parent.getWidth() - in.left - in.right);
            child.setBounds(in.left, in.top, Math.max(0, width), child.getPreferredSize().height);
        }
    }

    // 스크롤 창 안에서 폭은 창을 따르고 높이만 스크롤된다.
    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(java.awt.Rectangle r, int orientation, int direction) {
        return 24;
    }

    @Override
    public int getScrollableBlockIncrement(java.awt.Rectangle r, int orientation, int direction) {
        return r.height;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }

    /** 카드나 안내 글을 아래에 붙인다(앞의 것과 16 간격). */
    protected void addCard(Component card) {
        if (column.getComponentCount() > 4) {
            column.add(Box.createVerticalStrut(16));
        }
        if (card instanceof javax.swing.JComponent c) {
            c.setAlignmentX(LEFT_ALIGNMENT);
        }
        column.add(card);
    }

    /** 메뉴와 이동에 쓰는 이름표. */
    public String id() {
        return id;
    }

    public String menuLabel() {
        return menuLabel;
    }
}
