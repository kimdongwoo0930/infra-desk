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
 * macOS 메뉴 막대의 "편집" 메뉴(잘라내기 / 복사 / 붙여넣기 / 모두 선택).
 *
 * <p>한글 입력 소스가 켜져 있으면 macOS가 ⌘V를 입력기에 넘겨서 Java 텍스트 필드는 V를 받지
 * 못한다(--debug-input으로 확인: ⌘ 누름만 도착한다). 메뉴 단축키는 AppKit이 입력기보다 먼저
 * 처리하므로, 진짜 편집 메뉴를 두면 입력 소스와 관계없이 단축키가 동작한다. 포커스가 있는 곳에
 * 동작한다: 텍스트 필드든 터미널이든.
 */
public final class EditMenu {

    private EditMenu() {
    }

    /** 메뉴를 프레임에 설치하고 기본 메뉴 막대로도 설치한다(다이얼로그가 활성일 때 쓰인다). */
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
                    // 세션이 닫혔다.
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
