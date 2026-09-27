package com.infradesk.ui.components;

import com.formdev.flatlaf.util.SystemInfo;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import javax.swing.JMenuItem;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

/**
 * Right-click menu (잘라내기 / 복사 / 붙여넣기 / 모두 선택) for every text field in the app.
 * Swing text components have keyboard shortcuts but no context menu by default.
 */
public final class TextContextMenu {

    private TextContextMenu() {
    }

    /** Installs one global listener; call once at startup on the EDT. */
    public static void install() {
        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (event instanceof MouseEvent e && e.isPopupTrigger()
                    && e.getComponent() instanceof JTextComponent text && text.isEnabled()
                    && text.getComponentPopupMenu() == null) {
                show(text, e);
                e.consume();
            }
        }, AWTEvent.MOUSE_EVENT_MASK);
    }

    private static void show(JTextComponent text, MouseEvent e) {
        text.requestFocusInWindow();
        boolean editable = text.isEditable();
        boolean hasSelection = text.getSelectionStart() != text.getSelectionEnd();
        boolean secret = text instanceof JPasswordField;

        JPopupMenu menu = new JPopupMenu();
        menu.add(item("잘라내기", KeyEvent.VK_X, editable && hasSelection && !secret, text::cut));
        menu.add(item("복사", KeyEvent.VK_C, hasSelection && !secret, text::copy));
        menu.add(item("붙여넣기", KeyEvent.VK_V, editable && clipboardHasText(), text::paste));
        menu.addSeparator();
        menu.add(item("모두 선택", KeyEvent.VK_A, text.getDocument().getLength() > 0, text::selectAll));
        Component c = e.getComponent();
        menu.show(c, e.getX(), e.getY());
    }

    private static JMenuItem item(String label, int key, boolean enabled, Runnable action) {
        JMenuItem item = new JMenuItem(label);
        int mask = SystemInfo.isMacOS ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
        item.setAccelerator(KeyStroke.getKeyStroke(key, mask));
        item.setEnabled(enabled);
        item.addActionListener(ev -> SwingUtilities.invokeLater(action));
        return item;
    }

    private static boolean clipboardHasText() {
        try {
            return Toolkit.getDefaultToolkit().getSystemClipboard().isDataFlavorAvailable(DataFlavor.stringFlavor);
        } catch (IllegalStateException e) {
            return true;
        }
    }
}
