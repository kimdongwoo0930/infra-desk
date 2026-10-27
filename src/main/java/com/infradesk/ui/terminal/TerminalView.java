package com.infradesk.ui.terminal;

import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.infradesk.service.TerminalService;
import com.infradesk.ssh.Container;
import com.infradesk.ssh.DockerCommands;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

/**
 * 서버 화면의 "터미널" 탭 본문 (디자인 v2): 세션마다 탭 하나와 활성 터미널, 오른쪽에 저장된 명령어 패널.
 *
 * <p>세션은 모든 서버의 것이 하나의 목록에 있지만, 탭 줄에는 지금 보고 있는 서버({@link #showServer})의 세션만
 * 나온다. 다른 서버로 가도 그 서버의 세션은 닫히지 않고 숨어 있다가, 그 서버 화면으로 돌아오면 보던 탭 그대로
 * 다시 보인다. 보고 있는 서버에 세션이 없으면 빈 화면에 [접속] 버튼이 나온다(탭을 열기만 해서는 접속하지 않는다).
 */
public class TerminalView extends JPanel {

    private final TerminalService service;
    private final Consumer<Server> openSettings;
    private final JPanel tabStrip = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private final CardLayout cards = new CardLayout();
    private final JPanel terminals = new JPanel(cards);
    private final List<TerminalPanel> sessions = new ArrayList<>();
    private static final String EMPTY = "\0empty";

    /** 탭 줄의 [＋ 새 세션 ▾]: 누르면 이 서버의 새 셸과 컨테이너 셸·콘솔을 고르는 메뉴가 나온다. */
    private final JButton newSession = Buttons.ghost("새 세션 ▾");
    private final TerminalSidePanel sidePanel;
    /** 보고 있는 서버의 선택된 세션. 그 서버에 세션이 없으면 null. */
    private TerminalPanel active;
    /** 보고 있는 서버. 탭 줄에 어느 서버의 세션을 보여 줄지, 빈 화면의 [접속]이 누구에게 접속할지 정한다. */
    private Server shownServer;
    /** 서버마다 마지막으로 보던 세션. 다른 서버를 보고 돌아오면 이 탭이 다시 선택된다. */
    private final Map<String, TerminalPanel> lastActive = new HashMap<>();
    private final JLabel emptyHint = new JLabel(" ");
    private final JButton connectButton = Buttons.primary("접속", "terminal");
    private Runnable onCountChange = () -> { };
    private Runnable onConnectRequest = () -> { };
    /** 새 세션 메뉴에 보여 줄 보고 있는 서버의 컨테이너 목록과, 컨테이너 셸·콘솔을 여는 방법. */
    private Supplier<List<Container>> containerSource = List::of;
    private BiConsumer<Container, DockerCommands.Console> containerOpener = (container, console) -> { };

    public TerminalView(TerminalService service, Consumer<Server> openSettings) {
        super(new BorderLayout());
        this.service = service;
        this.openSettings = openSettings;
        setBackground(Theme.TERMINAL_BG);

        JPanel strip = new JPanel(new BorderLayout());
        strip.setBackground(Theme.TERMINAL_BAR_BG);
        strip.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.DIVIDER));
        strip.setPreferredSize(new Dimension(0, 38));
        tabStrip.setOpaque(false);
        newSession.setIcon(Icons.get("plus", 14, Theme.TEXT_MUTED));
        newSession.setIconTextGap(6);
        newSession.setForeground(Theme.TEXT_MUTED);
        newSession.setPreferredSize(new Dimension(112, 37));
        newSession.addActionListener(e -> showNewSessionMenu());
        tabStrip.add(newSession);
        strip.add(tabStrip, BorderLayout.CENTER);
        // 준비 중(v2): 터미널을 둘로 나눠 보는 분할 보기. 만들어지면 ComingSoon을 지우고 동작을 붙인다.
        JButton split = com.infradesk.ui.components.ComingSoon.apply(Buttons.icon("columns", "분할 보기", 32));
        JPanel splitWrap = new JPanel(new java.awt.GridBagLayout());
        splitWrap.setOpaque(false);
        splitWrap.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
        splitWrap.add(split);
        strip.add(splitWrap, BorderLayout.EAST);

        terminals.setBackground(Theme.TERMINAL_BG);
        terminals.add(emptyState(), EMPTY);
        cards.show(terminals, EMPTY);
        JPanel main = new JPanel(new BorderLayout());
        main.add(strip, BorderLayout.NORTH);
        main.add(terminals, BorderLayout.CENTER);
        sidePanel = new TerminalSidePanel(service, this::sendToActive);
        add(main, BorderLayout.CENTER);
        add(sidePanel, BorderLayout.EAST);
    }

    /** 보고 있는 서버의 선택된 터미널에 명령어를 보낸다(엔터까지). 연결된 터미널이 없으면 false. */
    public boolean send(String commandLine) {
        return active != null && active.send(commandLine);
    }

    /** 선택한 탭의 서버. 없으면 없음. */
    public java.util.Optional<Server> activeServer() {
        return active == null ? java.util.Optional.empty() : java.util.Optional.of(active.server());
    }

    private void sendToActive(String commandLine) {
        if (active == null || !active.send(commandLine)) {
            javax.swing.JOptionPane.showMessageDialog(this,
                    "연결된 터미널 탭이 없어요. 서버 세션을 먼저 열어 주세요.", "저장된 명령어",
                    javax.swing.JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /** 세션을 열거나 닫아서 수가 바뀌었을 때. 서버 화면의 탭 이름 옆 개수와 상태 막대가 이걸로 갱신한다. */
    public void onCountChange(Runnable listener) {
        this.onCountChange = listener;
    }

    /** 빈 화면의 [접속]을 눌렀다. 설정 확인 같은 접속 전 일은 부르는 쪽이 한다. */
    public void onConnectRequest(Runnable listener) {
        this.onConnectRequest = listener;
    }

    /** 열려 있는 세션 전체(모든 서버). */
    public int sessionCount() {
        return sessions.size();
    }

    /** 이 서버의 세션 수. */
    public int sessionCount(String serverId) {
        return sessionsOf(serverId).size();
    }

    private List<TerminalPanel> sessionsOf(String serverId) {
        List<TerminalPanel> mine = new ArrayList<>();
        for (TerminalPanel p : sessions) {
            if (p.server().id().equals(serverId)) {
                mine.add(p);
            }
        }
        return mine;
    }

    /**
     * 보여 줄 서버를 정한다. 탭 줄에는 이 서버의 세션만 나오고 다른 서버의 세션은 닫지 않고 숨겨 둔다. 서버 목록을
     * 새로 읽을 때마다 불리므로, 같은 서버면 고른 탭을 바꾸지 않는다(서버 상태만 새로 반영한다).
     */
    public void showServer(Server server) {
        boolean changed = shownServer == null || !shownServer.id().equals(server.id());
        shownServer = server;
        if (changed) {
            active = remembered(server.id());
        }
        refreshView();
    }

    /** 이 서버에서 마지막으로 보던 세션. 없거나 닫혔으면 첫 세션, 세션이 없으면 null. */
    private TerminalPanel remembered(String serverId) {
        TerminalPanel last = lastActive.get(serverId);
        if (last != null && sessions.contains(last)) {
            return last;
        }
        List<TerminalPanel> mine = sessionsOf(serverId);
        return mine.isEmpty() ? null : mine.getFirst();
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

    /** 컨테이너 셸·콘솔을 새 세션 메뉴에 넣기 위한 연결. 컨테이너는 서버 화면이 읽어 둔 목록을 쓴다. */
    public void setContainerSupport(Supplier<List<Container>> source, BiConsumer<Container, DockerCommands.Console> opener) {
        this.containerSource = source;
        this.containerOpener = opener;
    }

    /**
     * [＋ 새 세션 ▾]: 이 서버의 새 셸, 그리고 실행 중인 컨테이너의 셸과 데이터베이스 콘솔(이미지에 맞는 것만).
     * 서버가 꺼져 있으면 모두 비활성이다.
     */
    private void showNewSessionMenu() {
        if (shownServer == null) {
            return;
        }
        boolean running = shownServer.status() == ServerStatus.RUNNING;
        JPopupMenu menu = new JPopupMenu();
        JMenuItem shell = new JMenuItem(shownServer.name() + " 셸");
        shell.setEnabled(running);
        shell.addActionListener(e -> open(shownServer));
        menu.add(shell);
        List<Container> containers = containerSource.get().stream().filter(Container::isRunning).toList();
        if (running && !containers.isEmpty()) {
            menu.addSeparator();
            JMenuItem header = new JMenuItem("컨테이너");
            header.setEnabled(false);
            menu.add(header);
            for (Container c : containers) {
                JMenuItem item = new JMenuItem(c.name() + " 셸");
                item.addActionListener(e -> containerOpener.accept(c, null));
                menu.add(item);
                DockerCommands.Console.forImage(c.image()).ifPresent(console -> {
                    JMenuItem db = new JMenuItem(c.name() + " " + console.label + " 콘솔");
                    db.addActionListener(e -> containerOpener.accept(c, console));
                    menu.add(db);
                });
            }
        }
        menu.show(newSession, 0, newSession.getHeight());
    }

    private void add(TerminalPanel panel) {
        panel.onStateChange(this::rebuildTabs);
        sessions.add(panel);
        terminals.add(panel, cardName(panel));
        if (shownServer == null || !shownServer.id().equals(panel.server().id())) {
            shownServer = panel.server();   // 다른 서버에 연 세션이면 그 서버를 보여 준다
        }
        select(panel);
        panel.connect();
        onCountChange.run();
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

    private static String cardName(TerminalPanel panel) {
        return Integer.toHexString(System.identityHashCode(panel));
    }

    private void select(TerminalPanel panel) {
        active = panel;
        lastActive.put(panel.server().id(), panel);
        refreshView();
        panel.focusTerminal();
    }

    private void close(TerminalPanel panel) {
        String serverId = panel.server().id();
        int index = sessionsOf(serverId).indexOf(panel);
        panel.close();
        sessions.remove(panel);
        terminals.remove(panel);
        lastActive.remove(serverId, panel);
        onCountChange.run();
        if (panel != active) {
            rebuildTabs();
            return;
        }
        List<TerminalPanel> rest = sessionsOf(serverId);
        if (rest.isEmpty()) {
            active = null;
            refreshView();   // 마지막 탭을 닫으면 빈 화면(대시보드로 나가지 않는다)
        } else {
            select(rest.get(Math.max(0, index - 1)));
        }
    }

    /** 선택된 세션을 보여 주고(없으면 빈 화면), 탭 줄을 다시 만든다. */
    private void refreshView() {
        if (active == null) {
            cards.show(terminals, EMPTY);
            updateEmptyState();
        } else {
            cards.show(terminals, cardName(active));
        }
        rebuildTabs();
    }

    private void rebuildTabs() {
        tabStrip.removeAll();
        if (shownServer != null) {
            for (TerminalPanel p : sessionsOf(shownServer.id())) {
                tabStrip.add(new Tab(p, p == active));
            }
        }
        newSession.setEnabled(shownServer != null && shownServer.status() == ServerStatus.RUNNING);
        tabStrip.add(newSession);
        tabStrip.revalidate();
        tabStrip.repaint();
    }

    // ── 빈 화면 ─────────────────────────────────────────────────

    /** 보고 있는 서버에 세션이 없을 때: 안내와 [접속]. */
    private JPanel emptyState() {
        JLabel title = new JLabel("열린 터미널이 없어요");
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 16f));
        title.setAlignmentX(CENTER_ALIGNMENT);
        emptyHint.setForeground(Theme.TEXT_MUTED);
        emptyHint.setAlignmentX(CENTER_ALIGNMENT);
        connectButton.setAlignmentX(CENTER_ALIGNMENT);
        connectButton.addActionListener(e -> onConnectRequest.run());

        JPanel box = new JPanel();
        box.setOpaque(false);
        box.setLayout(new javax.swing.BoxLayout(box, javax.swing.BoxLayout.Y_AXIS));
        box.add(title);
        box.add(javax.swing.Box.createVerticalStrut(8));
        box.add(emptyHint);
        box.add(javax.swing.Box.createVerticalStrut(18));
        box.add(connectButton);

        JPanel center = new JPanel(new java.awt.GridBagLayout());
        center.setBackground(Theme.TERMINAL_BG);
        center.add(box);
        return center;
    }

    private void updateEmptyState() {
        boolean running = shownServer != null && shownServer.status() == ServerStatus.RUNNING;
        connectButton.setEnabled(running);
        emptyHint.setText(shownServer == null ? " "
                : running ? shownServer.name() + "에 SSH로 접속해서 셸을 열어요."
                        : "서버가 켜져 있을 때 접속할 수 있어요.");
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
