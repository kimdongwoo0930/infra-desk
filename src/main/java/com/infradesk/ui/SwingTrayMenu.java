package com.infradesk.ui;

import com.infradesk.ui.components.StatusDot;

import java.awt.Dimension;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.JDialog;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

/**
 * The tray menu on Windows, drawn with Swing/FlatLaf. Native AWT menus there render Hangul
 * as "???" and can't show emoji, so this replaces {@code TrayIcon.setPopupMenu}.
 *
 * <p>A JPopupMenu needs an invoker window: a tiny undecorated dialog is shown at the cursor
 * while the menu is open. When that dialog loses focus (a click anywhere else) the menu closes,
 * which is how native tray menus behave.
 */
final class SwingTrayMenu {

    private final JDialog anchor;
    private JPopupMenu popup;

    SwingTrayMenu() {
        anchor = new JDialog((Window) null);
        anchor.setUndecorated(true);
        anchor.setType(Window.Type.UTILITY);
        anchor.setSize(new Dimension(1, 1));
        anchor.setAlwaysOnTop(true);
        anchor.setBackground(new java.awt.Color(0, 0, 0, 0));
        anchor.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent e) {
                Window to = e.getOppositeWindow();
                if (popup != null && (to == null || !SwingUtilities.isDescendingFrom(to, anchor))) {
                    popup.setVisible(false);
                }
            }
        });
    }

    /** Opens the menu just above the cursor (the taskbar is usually at the bottom). */
    void show(List<TrayController.Entry> entries) {
        SwingUtilities.invokeLater(() -> {
            if (popup != null) {
                popup.setVisible(false);
            }
            popup = build(entries);
            popup.addPopupMenuListener(new PopupMenuListener() {
                @Override
                public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                }

                @Override
                public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                    anchor.setVisible(false);
                }

                @Override
                public void popupMenuCanceled(PopupMenuEvent e) {
                }
            });
            PointerInfo pointer = MouseInfo.getPointerInfo();
            Point at = pointer == null ? new Point(0, 0) : pointer.getLocation();
            Dimension size = popup.getPreferredSize();
            anchor.setLocation(at.x, at.y);
            anchor.setVisible(true);
            anchor.toFront();
            anchor.requestFocus();
            // JPopupMenu moves itself back on screen if this would overflow.
            popup.show(anchor, -size.width, -size.height);
        });
    }

    void dispose() {
        if (popup != null) {
            popup.setVisible(false);
        }
        anchor.dispose();
    }

    /** The popup for these entries; package-private for the UI snapshot tool. */
    static JPopupMenu build(List<TrayController.Entry> entries) {
        JPopupMenu menu = new JPopupMenu();
        add(menu, entries);
        return menu;
    }

    private static void add(javax.swing.JComponent menu, List<TrayController.Entry> entries) {
        for (TrayController.Entry e : entries) {
            switch (e) {
                case TrayController.Entry.Label l -> {
                    JMenuItem item = new JMenuItem(l.text());
                    item.setEnabled(false);
                    menu.add(item);
                }
                case TrayController.Entry.Item i -> {
                    JMenuItem item = new JMenuItem(i.text());
                    if (i.highlight()) {
                        item.setFont(item.getFont().deriveFont(java.awt.Font.BOLD));
                    }
                    item.addActionListener(ev -> i.action().run());
                    menu.add(item);
                }
                case TrayController.Entry.Separator ignored -> {
                    if (menu instanceof JPopupMenu p) {
                        p.addSeparator();
                    } else {
                        ((JMenu) menu).addSeparator();
                    }
                }
                case TrayController.Entry.ServerMenu sm -> {
                    JMenu sub = new JMenu(TrayController.plainLabel(sm.server(), sm.cpu()));
                    sub.setIcon(new StatusDot(sm.server().status()));
                    add(sub, sm.items());
                    menu.add(sub);
                }
            }
        }
    }

    /**
     * Whether the Windows taskbar uses the light theme, so the tray glyph can be drawn dark.
     * Missing value (older Windows 10) means the default dark taskbar.
     */
    static boolean lightTaskbar() {
        try {
            Process p = new ProcessBuilder("reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "SystemUsesLightTheme").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(3, TimeUnit.SECONDS);
            return out.contains("0x1");
        } catch (Exception e) {
            return false;
        }
    }
}
