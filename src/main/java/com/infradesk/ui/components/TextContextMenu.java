package com.infradesk.ui.components;

import com.formdev.flatlaf.util.SystemInfo;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Toolkit;
import java.awt.event.ContainerEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.JMenuItem;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.JTextComponent;

/**
 * Right-click menu (잘라내기 / 복사 / 붙여넣기 / 모두 선택) for every text field.
 *
 * <p>Attaches a standard {@code componentPopupMenu} to each text component as it's added, so
 * Swing opens and closes it the normal way. (An earlier version showed the menu by hand from a
 * global mouse listener; on macOS the dialog then stopped receiving input after a paste.)
 */
public final class TextContextMenu {

    private static final String INSTALLED = "infradesk.textContextMenu";

    private TextContextMenu() {
    }

    /** Installs once at startup on the EDT. */
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
            text.setComponentPopupMenu(menuFor(text));
        }
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                attach(child);
            }
        }
    }

    private static JPopupMenu menuFor(JTextComponent text) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem cut = item("잘라내기", KeyEvent.VK_X);
        JMenuItem copy = item("복사", KeyEvent.VK_C);
        JMenuItem paste = item("붙여넣기", KeyEvent.VK_V);
        JMenuItem all = item("모두 선택", KeyEvent.VK_A);
        cut.addActionListener(e -> text.cut());
        copy.addActionListener(e -> text.copy());
        paste.addActionListener(e -> text.paste());
        all.addActionListener(e -> text.selectAll());
        menu.add(cut);
        menu.add(copy);
        menu.add(paste);
        menu.addSeparator();
        menu.add(all);
        menu.addPopupMenuListener(new PopupMenuListener() {
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
        return menu;
    }

    private static JMenuItem item(String label, int key) {
        JMenuItem item = new JMenuItem(label);
        int mask = SystemInfo.isMacOS ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
        item.setAccelerator(KeyStroke.getKeyStroke(key, mask));
        return item;
    }
}
