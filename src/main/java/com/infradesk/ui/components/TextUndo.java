package com.infradesk.ui.components;

import com.formdev.flatlaf.util.SystemInfo;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ContainerEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.text.JTextComponent;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;

/**
 * 모든 텍스트 필드의 실행 취소 / 다시 실행(⌘Z / ⇧⌘Z, Windows는 Ctrl+Z / Ctrl+Y). Swing 텍스트
 * 필드에는 기본으로 없다. 텍스트 컴포넌트가 컨테이너에 추가될 때마다 붙인다.
 */
public final class TextUndo {

    static final String KEY = "infradesk.undoManager";

    private TextUndo() {
    }

    public static void install() {
        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (event instanceof ContainerEvent ce && ce.getID() == ContainerEvent.COMPONENT_ADDED) {
                attach(ce.getChild());
            }
        }, AWTEvent.CONTAINER_EVENT_MASK);
    }

    /** 컴포넌트에 실행 취소 기록이 있으면 마지막 편집을 취소한다. */
    public static void undo(Component c) {
        if (c instanceof JTextComponent t && t.getClientProperty(KEY) instanceof UndoManager m && m.canUndo()) {
            try {
                m.undo();
            } catch (CannotUndoException ignored) {
                // 취소할 것이 없다.
            }
        }
    }

    public static void redo(Component c) {
        if (c instanceof JTextComponent t && t.getClientProperty(KEY) instanceof UndoManager m && m.canRedo()) {
            try {
                m.redo();
            } catch (CannotRedoException ignored) {
                // 다시 실행할 것이 없다.
            }
        }
    }

    private static void attach(Component c) {
        if (c instanceof JTextComponent text && text.getClientProperty(KEY) == null) {
            UndoManager manager = new UndoManager();
            manager.setLimit(100);
            text.putClientProperty(KEY, manager);
            text.getDocument().addUndoableEditListener(manager);
            text.addPropertyChangeListener("document", e -> {
                manager.discardAllEdits();
                text.getDocument().addUndoableEditListener(manager);
            });
            int menu = SystemInfo.isMacOS ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
            text.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menu), "infradesk-undo");
            text.getInputMap(JComponent.WHEN_FOCUSED).put(SystemInfo.isMacOS
                    ? KeyStroke.getKeyStroke(KeyEvent.VK_Z, menu | InputEvent.SHIFT_DOWN_MASK)
                    : KeyStroke.getKeyStroke(KeyEvent.VK_Y, menu), "infradesk-redo");
            text.getActionMap().put("infradesk-undo", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    undo(text);
                }
            });
            text.getActionMap().put("infradesk-redo", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    redo(text);
                }
            });
        }
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                attach(child);
            }
        }
    }
}
