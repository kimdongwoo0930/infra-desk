package com.infradesk.ui.components;

import com.formdev.flatlaf.util.SystemInfo;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ContainerEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.JMenuItem;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.JTextComponent;

/**
 * 모든 텍스트 필드의 오른쪽 클릭 메뉴(잘라내기 / 복사 / 붙여넣기 / 모두 선택).
 *
 * <p>이력: 전역 마우스 리스너에서 띄우거나 나중에 표준 팝업으로 띄웠을 때 모두 macOS에서 모달
 * 계정 추가 다이얼로그가 멈췄다. 원인은 FlatLaf가 모든 팝업을 별도의 네이티브 창에 넣는 것이었다
 * (Theme의 {@code Popup.dropShadowPainted} 참고). 그것을 끄더라도 팝업이 창 안에 들어가지 않으면
 * Swing이 여전히 네이티브 창을 쓰므로, 이 메뉴는 항상 들어가도록 스스로 위치를 옮겨서 창 안의
 * 일반 팝업으로 유지한다.
 */
public final class TextContextMenu {

    private static final String INSTALLED = "infradesk.textContextMenu";

    private TextContextMenu() {
    }

    public static void install() {
        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (event instanceof ContainerEvent ce && ce.getID() == ContainerEvent.COMPONENT_ADDED) {
                attach(ce.getChild());
            }
        }, AWTEvent.CONTAINER_EVENT_MASK);
    }

    private static void attach(Component c) {
        if (c instanceof JTextComponent text && text.getComponentPopupMenu() == null
                && text.getClientProperty(INSTALLED) == null) {
            text.putClientProperty(INSTALLED, Boolean.TRUE);
            text.setComponentPopupMenu(new Menu(text));
        }
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                attach(child);
            }
        }
    }

    /** 항상 창의 루트 페인 안에 들어가도록 위치를 제한하는 팝업. */
    private static final class Menu extends JPopupMenu {

        Menu(JTextComponent text) {
            JMenuItem cut = item("잘라내기", KeyEvent.VK_X);
            JMenuItem copy = item("복사", KeyEvent.VK_C);
            JMenuItem paste = item("붙여넣기", KeyEvent.VK_V);
            JMenuItem all = item("모두 선택", KeyEvent.VK_A);
            cut.addActionListener(e -> text.cut());
            copy.addActionListener(e -> text.copy());
            paste.addActionListener(e -> text.paste());
            all.addActionListener(e -> text.selectAll());
            add(cut);
            add(copy);
            add(paste);
            addSeparator();
            add(all);
            setLightWeightPopupEnabled(true);
            addPopupMenuListener(new PopupMenuListener() {
                @Override
                public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                    boolean selection = text.getSelectionStart() != text.getSelectionEnd();
                    boolean secret = text instanceof JPasswordField;
                    cut.setEnabled(text.isEditable() && selection && !secret);
                    copy.setEnabled(selection && !secret);
                    paste.setEnabled(text.isEditable());
                    all.setEnabled(text.getDocument().getLength() > 0);
                }

                @Override
                public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                }

                @Override
                public void popupMenuCanceled(PopupMenuEvent e) {
                }
            });
        }

        @Override
        public void show(Component invoker, int x, int y) {
            JRootPane root = SwingUtilities.getRootPane(invoker);
            if (root != null) {
                Dimension size = getPreferredSize();
                Point p = SwingUtilities.convertPoint(invoker, x, y, root);
                Rectangle area = new Rectangle(0, 0, root.getWidth(), root.getHeight());
                p.x = Math.max(area.x, Math.min(p.x, area.x + area.width - size.width - 1));
                p.y = Math.max(area.y, Math.min(p.y, area.y + area.height - size.height - 1));
                Point back = SwingUtilities.convertPoint(root, p, invoker);
                x = back.x;
                y = back.y;
            }
            super.show(invoker, x, y);
        }

        private static JMenuItem item(String label, int key) {
            JMenuItem item = new JMenuItem(label);
            int mask = SystemInfo.isMacOS ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
            item.setAccelerator(KeyStroke.getKeyStroke(key, mask));
            return item;
        }
    }
}
