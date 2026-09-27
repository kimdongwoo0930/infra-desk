package com.infradesk.ui;

import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.infradesk.ui.components.StatusDot;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;

/** One 40px server row in the sidebar. Focusable; Enter/Space selects. */
class ServerListItem extends JPanel {

    private static final Color HOVER_BG = new Color(0x323438);

    private final Server server;
    private final JLabel name = new JLabel();
    private final JLabel detail = new JLabel();
    private boolean selected;
    private boolean hover;

    ServerListItem(Server server, Double cpuPercent, Runnable onSelect, Runnable onSshSettings) {
        super(new BorderLayout(10, 0));
        this.server = server;
        setOpaque(false);
        setFocusable(true);
        setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        setPreferredSize(new Dimension(0, 40));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        getAccessibleContext().setAccessibleName(server.name() + ", " + server.status().label());

        name.setText(server.name());
        name.setIcon(new StatusDot(server.status()));
        name.setIconTextGap(10);
        detail.setText(server.status() != ServerStatus.RUNNING ? server.status().label()
                : cpuPercent == null ? "" : Math.round(cpuPercent) + "%");
        if (cpuPercent != null && server.status() == ServerStatus.RUNNING) {
            detail.setToolTipText("CPU 사용률");
        }
        detail.putClientProperty("FlatLaf.style", "font: -2");
        add(name, BorderLayout.CENTER);
        add(detail, BorderLayout.EAST);
        applyColors();

        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem ssh = new javax.swing.JMenuItem("SSH 설정…");
        ssh.addActionListener(e -> onSshSettings.run());
        menu.add(ssh);
        setComponentPopupMenu(menu);

        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    return;
                }
                requestFocusInWindow();
                onSelect.run();
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                hover = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover = false;
                repaint();
            }
        });
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER || e.getKeyCode() == KeyEvent.VK_SPACE) {
                    onSelect.run();
                }
            }
        });
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                repaint();
            }
        });
    }

    Server server() {
        return server;
    }

    void setSelected(boolean selected) {
        this.selected = selected;
        applyColors();
        repaint();
    }

    private void applyColors() {
        name.setForeground(selected ? Color.WHITE : Theme.TEXT);
        detail.setForeground(selected ? new Color(0xB9C6E0) : Theme.TEXT_MUTED);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int arc = Theme.ARC_CONTROL * 2;
        if (selected) {
            g2.setColor(Theme.SELECTION_BG);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
        } else if (hover) {
            g2.setColor(HOVER_BG);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
        }
        if (isFocusOwner()) {
            g2.setColor(Theme.ACCENT);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
        }
        g2.dispose();
        super.paintComponent(g);
    }
}
