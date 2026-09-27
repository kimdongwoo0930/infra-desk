package com.infradesk.ui;

import com.formdev.flatlaf.util.SystemInfo;
import com.jediterm.terminal.model.SelectionUtil;
import com.jediterm.terminal.ui.JediTermWidget;

import java.awt.Component;
import java.awt.Desktop;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPasswordField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

/**
 * macOS menu-bar "편집" menu (잘라내기 / 복사 / 붙여넣기 / 모두 선택).
 *
 * <p>With a Korean input source active, macOS gives ⌘V to the input method and the Java text
 * field never sees the V (confirmed with --debug-input: only the ⌘ press arrives). Menu key
 * equivalents are handled by AppKit before the input method, so a real Edit menu makes the
 * shortcuts work regardless of input source. It acts on whatever has focus: text fields or the
 * terminal.
 */
public final class EditMenu {

    private EditMenu() {
    }

    /** Installs the menu on the frame and as the default menu bar (used while dialogs are active). */
    public static void install(JFrame frame) {
        if (!SystemInfo.isMacOS) {
            return;
        }
        frame.setJMenuBar(create());
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_MENU_BAR)) {
            Desktop.getDesktop().setDefaultMenuBar(create());
        }
    }

    private static JMenuBar create() {
        JMenu edit = new JMenu("편집");
        edit.add(item("실행 취소", KeyEvent.VK_Z, () -> com.infradesk.ui.components.TextUndo.undo(focused())));
        JMenuItem redo = item("다시 실행", KeyEvent.VK_Z, () -> com.infradesk.ui.components.TextUndo.redo(focused()));
        redo.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        edit.add(redo);
        edit.addSeparator();
        edit.add(item("잘라내기", KeyEvent.VK_X, EditMenu::cut));
        edit.add(item("복사", KeyEvent.VK_C, EditMenu::copy));
        edit.add(item("붙여넣기", KeyEvent.VK_V, EditMenu::paste));
        edit.addSeparator();
        edit.add(item("모두 선택", KeyEvent.VK_A, EditMenu::selectAll));
        JMenuBar bar = new JMenuBar();
        bar.add(edit);
        return bar;
    }

    private static JMenuItem item(String label, int key, Runnable action) {
        JMenuItem item = new JMenuItem(label);
        item.setAccelerator(KeyStroke.getKeyStroke(key, InputEvent.META_DOWN_MASK));
        item.addActionListener(e -> SwingUtilities.invokeLater(action));
        return item;
    }

    private static Component focused() {
        return KeyboardFocusManager.getCurrentKeyboardFocusManager().getPermanentFocusOwner();
    }

    private static JediTermWidget terminal(Component c) {
        return c == null ? null : (JediTermWidget) SwingUtilities.getAncestorOfClass(JediTermWidget.class, c);
    }

    private static void cut() {
        if (focused() instanceof JTextComponent t && !(t instanceof JPasswordField) && t.isEditable()) {
            t.cut();
        }
    }

    private static void copy() {
        Component c = focused();
        if (c instanceof JTextComponent t && !(t instanceof JPasswordField)) {
            t.copy();
            return;
        }
        JediTermWidget term = terminal(c);
        if (term != null && term.getTerminalPanel().getSelection() != null) {
            String text = SelectionUtil.getSelectionText(term.getTerminalPanel().getSelection(), term.getTerminalTextBuffer());
            if (text != null && !text.isEmpty()) {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
            }
        }
    }

    private static void paste() {
        Component c = focused();
        if (c instanceof JTextComponent t && t.isEditable()) {
            t.paste();
            return;
        }
        JediTermWidget term = terminal(c);
        if (term != null && term.getTtyConnector() != null) {
            String text = clipboardText();
            if (text != null && !text.isEmpty()) {
                try {
                    term.getTtyConnector().write(text.getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // Session closed.
                }
            }
        }
    }

    private static void selectAll() {
        Component c = focused();
        if (c instanceof JTextComponent t) {
            t.selectAll();
            return;
        }
        JediTermWidget term = terminal(c);
        if (term != null) {
            term.getTerminalPanel().selectAll();
        }
    }

    private static String clipboardText() {
        try {
            return (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
        } catch (UnsupportedFlavorException | IOException | IllegalStateException e) {
            return null;
        }
    }
}
