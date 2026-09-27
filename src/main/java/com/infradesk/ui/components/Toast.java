package com.infradesk.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.ui.Theme;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JWindow;
import javax.swing.Timer;

/** Small notice in the owner window's bottom-right corner that disappears after a few seconds. */
public final class Toast {

    private static final int WIDTH = 340;
    private static JWindow current;

    private Toast() {
    }

    /** Must be called on the EDT. Click to dismiss. */
    public static void show(Window owner, String heading, String title, String body, Color accent) {
        if (owner == null || !owner.isShowing()) {
            return;
        }
        if (current != null) {
            current.dispose();
        }
        JWindow w = new JWindow(owner);
        RoundedPanel panel = new RoundedPanel(new BorderLayout(0, 4), Theme.PANEL_BG, Theme.INPUT_BORDER, Theme.ARC_CARD);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, accent),
                BorderFactory.createEmptyBorder(10, 12, 12, 12)));
        JLabel head = new JLabel(heading);
        head.setForeground(Theme.TEXT_MUTED);
        head.putClientProperty(FlatClientProperties.STYLE, "font: -2");
        JLabel t = new JLabel(title);
        t.setFont(t.getFont().deriveFont(Font.BOLD));
        JTextArea b = new JTextArea(body);
        b.setLineWrap(true);
        b.setWrapStyleWord(false);
        b.setEditable(false);
        b.setOpaque(false);
        b.setForeground(Theme.TEXT_SECONDARY);
        b.setSize(new Dimension(WIDTH - 30, Short.MAX_VALUE));
        JPanel top = new JPanel(new BorderLayout(0, 2));
        top.setOpaque(false);
        top.add(head, BorderLayout.NORTH);
        top.add(t, BorderLayout.CENTER);
        panel.add(top, BorderLayout.NORTH);
        panel.add(b, BorderLayout.CENTER);
        w.setContentPane(panel);
        w.setBackground(new Color(0, 0, 0, 0));
        w.pack();
        w.setSize(WIDTH, w.getHeight());
        Point p = owner.getLocationOnScreen();
        w.setLocation(p.x + owner.getWidth() - WIDTH - 20, p.y + owner.getHeight() - w.getHeight() - 20);
        MouseAdapter dismiss = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                w.dispose();
            }
        };
        panel.addMouseListener(dismiss);
        b.addMouseListener(dismiss);
        w.setVisible(true);
        current = w;
        Timer timer = new Timer(7000, e -> w.dispose());
        timer.setRepeats(false);
        timer.start();
    }
}
