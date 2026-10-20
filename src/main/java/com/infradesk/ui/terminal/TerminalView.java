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

/** 터미널 화면: 세션마다 탭 하나와 활성 터미널. */
public class TerminalView extends JPanel {

    private final TerminalService service;
    private final Consumer<Server> openSettings;
    private final Supplier<List<Server>> connectableServers;
    private final JPanel tabStrip = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private final CardLayout cards = new CardLayout();
    private final JPanel terminals = new JPanel(cards);
    private final List<TerminalPanel> sessions = new ArrayList<>();
    private final JButton newSession = Buttons.icon("plus", "새 세션", 38);
    private final TerminalSidePanel sidePanel;
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
        JPanel main = new JPanel(new BorderLayout());
        main.add(strip, BorderLayout.NORTH);
        main.add(terminals, BorderLayout.CENTER);
        sidePanel = new TerminalSidePanel(service, connectableServers, this::sendToActive);
        sidePanel.sftpButton().addActionListener(e -> openSftp());
        add(main, BorderLayout.CENTER);
        add(sidePanel, BorderLayout.EAST);
    }

    /** 화면이 보일 때 호출되어 일괄 실행 체크박스를 최신 서버 목록에 맞춘다. */
    public void refreshServers() {
        sidePanel.refreshServers();
    }

    public JButton sftpButton() {
        return sidePanel.sftpButton();
    }

    /** 선택한 탭의 서버. 없으면 없음. */
    public java.util.Optional<Server> activeServer() {
        return active == null ? java.util.Optional.empty() : java.util.Optional.of(active.server());
    }

    /** 활성 탭 서버의 SFTP. 열린 탭이 없으면 실행 중인 서버 메뉴. */
    private void openSftp() {
        if (active != null) {
            openSftp(active.server());
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        for (Server s : connectableServers.get()) {
            JMenuItem item = new JMenuItem(s.name(), new com.infradesk.ui.components.StatusDot(s.status()));
            item.setEnabled(s.status() == ServerStatus.RUNNING);
            item.addActionListener(e -> openSftp(s));
            menu.add(item);
        }
        menu.show(sidePanel.sftpButton(), 0, -menu.getPreferredSize().height);
    }

    private void openSftp(Server server) {
        if (!service.isConfigured(server.id())) {
            openSettings.accept(server);
            if (!service.isConfigured(server.id())) {
                return;
            }
        }
        new SftpDialog(javax.swing.SwingUtilities.getWindowAncestor(this), service, server).setVisible(true);
    }

    private void sendToActive(String commandLine) {
        if (active == null || !active.send(commandLine)) {
            javax.swing.JOptionPane.showMessageDialog(this,
                    "연결된 터미널 탭이 없어요. 서버 세션을 먼저 열어 주세요.", "저장된 명령어",
                    javax.swing.JOptionPane.INFORMATION_MESSAGE);
        }
    }

    public void onCountChange(IntConsumer listener) {
        this.onCountChange = listener;
    }

    /** 마지막 탭이 닫히면 호출된다. */
    public void onEmpty(Runnable listener) {
        this.onEmpty = listener;
    }

    public int sessionCount() {
        return sessions.size();
    }

    /** 서버의 기존 세션을 선택하거나, 없으면 새로 연다. */
    public void openOrSelect(Server server) {
        for (TerminalPanel p : sessions) {
            if (p.isLoginShell() && p.server().id().equals(server.id())) {
                select(p);
                if (p.state() == TerminalPanel.State.DISCONNECTED) {
                    p.connect();
                }
                return;
            }
        }
        open(server);
    }

    /** 서버의 새 세션 탭을 연다. */
    public void open(Server server) {
        add(new TerminalPanel(server, service, openSettings));
    }

    /** 서버에서 {@code exec}를 실행하는 탭을 연다(컨테이너 셸 또는 데이터베이스 콘솔). */
    public void open(Server server, TerminalPanel.Exec exec) {
        add(new TerminalPanel(server, exec, service, openSettings));
    }

    private void add(TerminalPanel panel) {
        panel.onStateChange(this::rebuildTabs);
        sessions.add(panel);
        terminals.add(panel, Integer.toHexString(System.identityHashCode(panel)));
        select(panel);
        panel.connect();
        onCountChange.accept(sessions.size());
    }

    /** 서버의 모든 탭을 다시 연결한다(예: SSH 설정이 바뀐 뒤). */
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

    /** 탭 하나: 상태 점, 서버 이름, 닫기 버튼. 선택한 탭은 위쪽에 2px 강조색 테두리가 있다. */
    private final class Tab extends JPanel {

        private final boolean selected;

        Tab(TerminalPanel panel, boolean selected) {
            super(new FlowLayout(FlowLayout.LEFT, 8, 0));
            this.selected = selected;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 2));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            JLabel label = new JLabel(panel.title(), new DotIcon(panel), JLabel.LEFT);
            label.setIconTextGap(8);
            label.setForeground(selected ? Theme.TEXT : Theme.TEXT_MUTED);
            label.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
            JButton close = Buttons.icon("x", panel.title() + " 세션 닫기", 22);
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

    /** 세션의 연결 색으로 칠한 7px 점. */
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
