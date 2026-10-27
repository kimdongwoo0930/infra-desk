package com.infradesk.ui;

import com.infradesk.ui.components.ComingSoon;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 사이드바 맨 위의 이동 항목 하나 (디자인 v2의 [개요] [활동 기록]): 아이콘과 이름, 높이 32. 서버 행처럼 포커스를 받고
 * Enter/Space로도 누를 수 있다. 아직 기능이 없는 항목은 {@link #comingSoon}으로 눌리지 않게 하고 "준비 중"을 붙인다.
 */
final class SidebarNavItem extends JPanel {

    private static final Color HOVER_BG = new Color(0x323438);

    private final JLabel label = new JLabel();
    private final String iconName;
    private final Runnable onPress;
    private boolean selected;
    private boolean hover;
    private boolean disabled;

    SidebarNavItem(String text, String iconName, Runnable onPress) {
        super(new BorderLayout(10, 0));
        this.iconName = iconName;
        this.onPress = onPress;
        setOpaque(false);
        setFocusable(true);
        setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
        setPreferredSize(new Dimension(0, 32));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.setText(text);
        label.setIconTextGap(10);
        add(label, BorderLayout.CENTER);
        getAccessibleContext().setAccessibleName(text);
        applyStyle();

        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!disabled) {
                    requestFocusInWindow();
                    onPress.run();
                }
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                hover = !disabled;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover = false;
                repaint();
            }
        });
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (!disabled && (e.getKeyCode() == KeyEvent.VK_ENTER || e.getKeyCode() == KeyEvent.VK_SPACE)) {
                    onPress.run();
                }
            }
        });
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                repaint();
            }
        });
    }

    boolean isSelected() {
        return selected;
    }

    void setSelected(boolean selected) {
        this.selected = selected;
        applyStyle();
        repaint();
    }

    /** 오른쪽에 붙이는 작은 부품(개수 알약, "준비 중" 알약). */
    void setTrailing(javax.swing.JComponent component) {
        add(component, BorderLayout.EAST);
        revalidate();
    }

    /** 아직 기능이 없다: 눌러도 아무 일도 없고, 흐리게 보이며 "준비 중"을 붙인다. */
    void comingSoon() {
        disabled = true;
        setFocusable(false);
        setCursor(Cursor.getDefaultCursor());
        setTrailing(ComingSoon.badge());
        setToolTipText(ComingSoon.TOOLTIP);
        applyStyle();
    }

    /** 사이드바가 좁은 모드면 이름만 남기지 않고 아이콘만 보인다. */
    void setCompact(boolean compact) {
        label.setText(compact ? "" : label.getAccessibleContext().getAccessibleName());
        if (getComponentCount() > 1) {
            getComponent(1).setVisible(!compact);
        }
        revalidate();
    }

    private void applyStyle() {
        Color color = disabled ? Theme.TEXT_MUTED : selected ? Color.WHITE : Theme.TEXT_SECONDARY;
        label.setForeground(color);
        label.setIcon(Icons.get(iconName, 15, color));
        label.setFont(label.getFont().deriveFont(selected ? Font.BOLD : Font.PLAIN));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int arc = Theme.ARC_CONTROL * 2;
        if (selected) {
            g2.setColor(Theme.SELECTION_BG);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
        } else if (hover) {
            g2.setColor(HOVER_BG);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
        }
        if (isFocusOwner()) {
            g2.setColor(Theme.ACCENT);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
        }
        g2.dispose();
        super.paintComponent(g);
    }
}
