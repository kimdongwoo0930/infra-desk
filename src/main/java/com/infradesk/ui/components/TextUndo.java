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
 * Undo / redo (⌘Z / ⇧⌘Z, Ctrl+Z / Ctrl+Y on Windows) for every text field. Swing text fields have
 * none by default. Attached to each text component as it's added to a container.
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

    /** Undoes the last edit in the component, if it has undo history. */
    public static void undo(Component c) {
        if (c instanceof JTextComponent t && t.getClientProperty(KEY) instanceof UndoManager m && m.canUndo()) {
            try {
                m.undo();
            } catch (CannotUndoException ignored) {
                // Nothing to undo.
            }
        }
    }

    public static void redo(Component c) {
        if (c instanceof JTextComponent t && t.getClientProperty(KEY) instanceof UndoManager m && m.canRedo()) {
            try {
                m.redo();
            } catch (CannotRedoException ignored) {
                // Nothing to redo.
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
