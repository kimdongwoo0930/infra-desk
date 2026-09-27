package com.infradesk.ui.terminal;

import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.infradesk.service.TerminalService;
import com.infradesk.ui.Icons;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

/** Terminal screen: a tab per session plus the active terminal. */
public class TerminalView extends JPanel {

    private final TerminalService service;
    private final Consumer<Server> openSettings;
    private final Supplier<List<Server>> connectableServers;
    private final JPanel tabStrip = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private final CardLayout cards = new CardLayout();
    private final JPanel terminals = new JPanel(cards);
    private final List<TerminalPanel> sessions = new ArrayList<>();
    private final JButton newSession = Buttons.icon("plus", "새 세션", 38);
    private TerminalPanel active;
    private IntConsumer onCountChange = n -> { };
    private Runnable onEmpty = () -> { };

    public TerminalView(TerminalService service, Consumer<Server> openSettings, Supplier<List<Server>> connectableServers) {
        super(new BorderLayout());
        this.service = service;
        this.openSettings = openSettings;
        this.connectableServers = connectableServers;
        setBackground(Theme.TERMINAL_BG);

        JPanel strip = new JPanel(new BorderLayout());
        strip.setBackground(Theme.TERMINAL_BAR_BG);
        strip.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.DIVIDER));
        strip.setPreferredSize(new Dimension(0, 38));
        tabStrip.setOpaque(false);
        newSession.setPreferredSize(new Dimension(38, 37));
        newSession.addActionListener(e -> showNewSessionMenu());
        tabStrip.add(newSession);
        strip.add(tabStrip, BorderLayout.CENTER);

        terminals.setBackground(Theme.TERMINAL_BG);
        add(strip, BorderLayout.NORTH);
        add(terminals, BorderLayout.CENTER);
    }

    public void onCountChange(IntConsumer listener) {
        this.onCountChange = listener;
    }

    /** Called when the last tab is closed. */
    public void onEmpty(Runnable listener) {
        this.onEmpty = listener;
    }

    public int sessionCount() {
        return sessions.size();
    }

    /** Selects the existing session for the server, or opens a new one. */
    public void openOrSelect(Server server) {
        for (TerminalPanel p : sessions) {
            if (p.server().id().equals(server.id())) {
                select(p);
                if (p.state() == TerminalPanel.State.DISCONNECTED) {
                    p.connect();
                }
                return;
            }
        }
        open(server);
    }

    /** Opens a new session tab for the server. */
    public void open(Server server) {
        TerminalPanel panel = new TerminalPanel(server, service, openSettings);
        panel.onStateChange(this::rebuildTabs);
        sessions.add(panel);
        terminals.add(panel, Integer.toHexString(System.identityHashCode(panel)));
        select(panel);
        panel.connect();
        onCountChange.accept(sessions.size());
    }

    /** Reconnects every tab for the server (e.g. after its SSH settings changed). */
    public void reconnect(String serverId) {
        for (TerminalPanel p : sessions) {
            if (p.server().id().equals(serverId) && p.state() == TerminalPanel.State.DISCONNECTED) {
                p.connect();
            }
        }
    }

    public void focusActive() {
        if (active != null) {
            active.focusTerminal();
        }
    }

    public void closeAll() {
        for (TerminalPanel p : List.copyOf(sessions)) {
            p.close();
        }
    }

    private void select(TerminalPanel panel) {
        active = panel;
        cards.show(terminals, Integer.toHexString(System.identityHashCode(panel)));
        rebuildTabs();
        panel.focusTerminal();
    }

    private void close(TerminalPanel panel) {
        int index = sessions.indexOf(panel);
        panel.close();
        sessions.remove(panel);
        terminals.remove(panel);
        onCountChange.accept(sessions.size());
        if (sessions.isEmpty()) {
            active = null;
            rebuildTabs();
            onEmpty.run();
            return;
        }
        if (panel == active) {
            select(sessions.get(Math.max(0, index - 1)));
        } else {
            rebuildTabs();
        }
    }

    private void rebuildTabs() {
        tabStrip.removeAll();
        for (TerminalPanel p : sessions) {
            tabStrip.add(new Tab(p, p == active));
        }
        tabStrip.add(newSession);
        tabStrip.revalidate();
        tabStrip.repaint();
    }

    private void showNewSessionMenu() {
        JPopupMenu menu = new JPopupMenu();
        List<Server> servers = connectableServers.get();
        if (servers.isEmpty()) {
            JMenuItem none = new JMenuItem("실행 중인 서버가 없어요");
            none.setEnabled(false);
            menu.add(none);
        }
        for (Server s : servers) {
            JMenuItem item = new JMenuItem(s.name(), new com.infradesk.ui.components.StatusDot(s.status()));
            item.setEnabled(s.status() == ServerStatus.RUNNING);
            item.addActionListener(e -> open(s));
            menu.add(item);
        }
        menu.show(newSession, 0, newSession.getHeight());
    }

    /** One tab: status dot, server name, close button. Selected tab has a 2px accent top border. */
    private final class Tab extends JPanel {

        private final boolean selected;

        Tab(TerminalPanel panel, boolean selected) {
            super(new FlowLayout(FlowLayout.LEFT, 8, 0));
            this.selected = selected;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 2));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            JLabel label = new JLabel(panel.server().name(), new DotIcon(panel), JLabel.LEFT);
            label.setIconTextGap(8);
            label.setForeground(selected ? Theme.TEXT : Theme.TEXT_MUTED);
            label.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
            JButton close = Buttons.icon("x", panel.server().name() + " 세션 닫기", 22);
            close.setIcon(Icons.get("x", 12));
            close.addActionListener(e -> close(panel));
            JPanel closeWrap = new JPanel(new BorderLayout());
            closeWrap.setOpaque(false);
            closeWrap.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
            closeWrap.add(close);
            add(label);
            add(closeWrap);
            setPreferredSize(new Dimension(getLayout().preferredLayoutSize(this).width + 8, 37));

            MouseAdapter click = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON2) {
                        close(panel);
                    } else {
                        select(panel);
                    }
                }
            };
            addMouseListener(click);
            label.addMouseListener(click);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            if (selected) {
                g2.setColor(Theme.TERMINAL_BG);
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(Theme.ACCENT);
                g2.fillRect(0, 0, getWidth(), 2);
            }
            g2.setColor(Theme.DIVIDER);
            g2.setStroke(new BasicStroke(1));
            g2.drawLine(getWidth() - 1, 0, getWidth() - 1, getHeight());
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** 7px dot in the session's connection color. */
    private record DotIcon(TerminalPanel panel) implements javax.swing.Icon {
        @Override
        public void paintIcon(java.awt.Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(panel.stateColor());
            g2.fillOval(x, y, 7, 7);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 7;
        }

        @Override
        public int getIconHeight() {
            return 7;
        }
    }
}
