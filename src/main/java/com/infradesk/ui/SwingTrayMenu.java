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
 * Windows의 트레이 메뉴. Swing/FlatLaf로 그린다. 그곳의 네이티브 AWT 메뉴는 한글을
 * "???"로 그리고 이모지를 표시하지 못하므로, 이것이 {@code TrayIcon.setPopupMenu}를 대체한다.
 *
 * <p>JPopupMenu에는 invoker 창이 필요하다. 메뉴가 열려 있는 동안 커서 위치에 장식 없는 작은
 * 다이얼로그를 띄운다. 이 다이얼로그가 포커스를 잃으면(다른 곳을 클릭하면) 메뉴가 닫히며,
 * 네이티브 트레이 메뉴도 그렇게 동작한다.
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

    /** 커서 바로 위에 메뉴를 연다(작업 표시줄은 보통 아래에 있다). */
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
            // 이대로면 화면을 벗어나더라도 JPopupMenu가 스스로 화면 안으로 옮긴다.
            popup.show(anchor, -size.width, -size.height);
        });
    }

    void dispose() {
        if (popup != null) {
            popup.setVisible(false);
        }
        anchor.dispose();
    }

    /** 이 항목들의 팝업. UI 스냅샷 도구를 위해 package-private. */
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
     * Windows 작업 표시줄이 밝은 테마인지. 트레이 글리프를 어둡게 그릴 수 있도록 확인한다.
     * 값이 없으면(오래된 Windows 10) 기본값인 어두운 작업 표시줄이다.
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
