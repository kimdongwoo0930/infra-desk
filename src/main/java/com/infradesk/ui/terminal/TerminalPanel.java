package com.infradesk.ui.terminal;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.core.Server;
import com.infradesk.service.TerminalService;
import com.infradesk.ssh.ShellSession;
import com.infradesk.ssh.SshException;
import com.infradesk.ui.Async;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;
import com.jediterm.terminal.ui.JediTermWidget;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** 터미널 탭 하나: 연결하고, JediTerm 위젯과 상태 표시줄을 보여준다. */
public class TerminalPanel extends JPanel {

    public enum State { CONNECTING, CONNECTED, DISCONNECTED }

    private static final int INITIAL_COLUMNS = 120;
    private static final int INITIAL_ROWS = 32;

    /** 로그인 셸 대신 명령 하나를 실행하는 탭. 예: {@code docker exec -it}. */
    public record Exec(String title, String command) {
    }

    private final Server server;
    private final Exec exec;
    private final TerminalService service;
    private final Consumer<Server> openSettings;
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final JLabel statusLeft = new JLabel();
    private final JLabel statusRight = new JLabel();
    private final JLabel messageTitle = new JLabel();
    private final JLabel messageBody = new JLabel();
    private final JButton reconnect = Buttons.primary("다시 연결", "refresh");
    private final JButton settings = Buttons.secondary("SSH 설정", "key");

    private JediTermWidget widget;
    private ShellSession session;
    private State state = State.DISCONNECTED;
    private Runnable onStateChange = () -> { };

    public TerminalPanel(Server server, TerminalService service, Consumer<Server> openSettings) {
        this(server, null, service, openSettings);
    }

    /** @param exec 로그인 셸 대신 실행할 것. 일반 셸이면 null */
    public TerminalPanel(Server server, Exec exec, TerminalService service, Consumer<Server> openSettings) {
        super(new BorderLayout());
        this.server = server;
        this.exec = exec;
        this.service = service;
        this.openSettings = openSettings;
        setBackground(Theme.TERMINAL_BG);
        center.setBackground(Theme.TERMINAL_BG);
        center.add(messagePanel(), "message");
        add(center, BorderLayout.CENTER);
        add(statusBar(), BorderLayout.SOUTH);
        reconnect.addActionListener(e -> connect());
        com.infradesk.ui.IpPrivacy.onChange(() -> statusLeft.setText(com.infradesk.ui.IpPrivacy.mask(statusText)));
        settings.addActionListener(e -> openSettings.accept(server));
    }

    public Server server() {
        return server;
    }

    /** 순수한 로그인 셸이면 true(컨테이너 셸이나 콘솔이 아님). */
    public boolean isLoginShell() {
        return exec == null;
    }

    /** 탭 제목: 서버, 또는 "서버 › 컨테이너". */
    public String title() {
        return exec == null ? server.name() : server.name() + " › " + exec.title();
    }

    public State state() {
        return state;
    }

    public void onStateChange(Runnable listener) {
        this.onStateChange = listener;
    }

    public void connect() {
        if (state == State.CONNECTING) {
            return;
        }
        disposeWidget();
        setState(State.CONNECTING, "연결하는 중…", null);
        showMessage("연결하는 중…", exec == null ? server.name() + "에 SSH로 연결하고 있어요."
                : server.name() + "에 연결해서 " + exec.title() + "을(를) 열고 있어요.", false);
        Async.run(() -> exec == null
                        ? service.open(server, new HostKeyDialog(this), INITIAL_COLUMNS, INITIAL_ROWS)
                        : service.open(server, exec.command(), new HostKeyDialog(this), INITIAL_COLUMNS, INITIAL_ROWS),
                this::attach,
                err -> {
                    java.util.logging.Logger.getLogger(TerminalPanel.class.getName()).log(java.util.logging.Level.WARNING,
                            "SSH connect failed for " + server.name() + ": " + Async.message(err), err);
                    setState(State.DISCONNECTED, "연결 실패", null);
                    boolean needsSettings = err instanceof SshException se
                            && (se.kind() == SshException.Kind.AUTH || se.kind() == SshException.Kind.KEY_FORMAT);
                    showMessage("연결하지 못했어요", Async.message(err), needsSettings);
                });
    }

    private void attach(ShellSession shell) {
        java.util.logging.Logger.getLogger(TerminalPanel.class.getName()).info(() -> "SSH connected to " + server.name()
                + " as " + shell.address());
        this.session = shell;
        widget = new JediTermWidget(INITIAL_COLUMNS, INITIAL_ROWS, new TerminalSettings());
        widget.setBorder(BorderFactory.createEmptyBorder(8, 10, 0, 4));
        widget.setBackground(Theme.TERMINAL_BG);
        widget.setTtyConnector(new ShellTtyConnector(shell));
        center.add(widget, "terminal");
        cards.show(center, "terminal");
        widget.start();
        setState(State.CONNECTED, "연결됨 · " + shell.address(), "UTF-8 · " + INITIAL_COLUMNS + "×" + INITIAL_ROWS);
        SwingUtilities.invokeLater(widget::requestFocusInWindow);

        Thread.ofVirtual().name("ssh-wait-" + server.name()).start(() -> {
            try {
                shell.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            SwingUtilities.invokeLater(() -> {
                if (session == shell) {
                    setState(State.DISCONNECTED, "연결 끊김", null);
                    showMessage(exec == null ? "연결이 끊겼어요" : "종료됐어요", title() + " 세션이 끝났어요.", false);
                }
            });
        });
    }

    /** 세션을 닫는다. 여러 번 호출해도 안전하다. */
    public void close() {
        ShellSession s = session;
        session = null;
        disposeWidget();
        if (s != null) {
            Thread.ofVirtual().start(s::close);
        }
    }

    /** 명령줄을 셸에 입력하고 Enter를 누른다. 연결되어 있지 않으면 false. */
    public boolean send(String commandLine) {
        if (widget == null || state != State.CONNECTED || widget.getTtyConnector() == null) {
            return false;
        }
        try {
            widget.getTtyConnector().write(commandLine + "\r");
            widget.requestFocusInWindow();
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    public void focusTerminal() {
        if (widget != null) {
            widget.requestFocusInWindow();
        }
    }

    private void disposeWidget() {
        if (widget != null) {
            JediTermWidget w = widget;
            widget = null;
            center.remove(w);
            Thread.ofVirtual().start(w::close);
        }
    }

    private String statusText = "";

    private void setState(State newState, String left, String right) {
        state = newState;
        statusText = left;
        statusLeft.setText(com.infradesk.ui.IpPrivacy.mask(left));
        statusLeft.setIcon(new com.infradesk.ui.components.StatusDot(switch (newState) {
            case CONNECTED -> com.infradesk.core.ServerStatus.RUNNING;
            case CONNECTING -> com.infradesk.core.ServerStatus.STARTING;
            case DISCONNECTED -> com.infradesk.core.ServerStatus.STOPPED;
        }));
        statusRight.setText(right == null ? "" : right);
        onStateChange.run();
    }

    /** @param offerSettings SSH 설정을 고치는 것이 해결책일 가능성이 높으면 true. 설정 버튼이 포커스를 받는다 */
    private void showMessage(String title, String body, boolean offerSettings) {
        messageTitle.setText(title);
        messageBody.setText("<html><div style='text-align:center'>" + escape(com.infradesk.ui.IpPrivacy.mask(body)) + "</div></html>");
        boolean waiting = state == State.CONNECTING;
        reconnect.setVisible(!waiting);
        settings.setVisible(!waiting);
        cards.show(center, "message");
        if (offerSettings) {
            SwingUtilities.invokeLater(settings::requestFocusInWindow);
        }
    }

    private JPanel messagePanel() {
        JPanel wrap = new JPanel(new GridBagLayout());
        wrap.setBackground(Theme.TERMINAL_BG);
        JPanel box = new JPanel();
        box.setOpaque(false);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        messageTitle.putClientProperty(FlatClientProperties.STYLE, "font: +4 bold");
        messageBody.setForeground(Theme.TEXT_SECONDARY);
        messageBody.setMaximumSize(new java.awt.Dimension(520, 200));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        buttons.setOpaque(false);
        buttons.add(reconnect);
        buttons.add(settings);
        for (javax.swing.JComponent c : new javax.swing.JComponent[] {messageTitle, messageBody, buttons}) {
            c.setAlignmentX(Component.CENTER_ALIGNMENT);
        }
        box.add(messageTitle);
        box.add(Box.createVerticalStrut(8));
        box.add(messageBody);
        box.add(Box.createVerticalStrut(16));
        box.add(buttons);
        wrap.add(box);
        return wrap;
    }

    private JPanel statusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(Theme.TERMINAL_BAR_BG);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.DIVIDER),
                BorderFactory.createEmptyBorder(6, 14, 6, 14)));
        for (JLabel l : new JLabel[] {statusLeft, statusRight}) {
            l.setForeground(Theme.TEXT_MUTED);
            l.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        }
        statusLeft.setIconTextGap(6);
        bar.add(statusLeft, BorderLayout.WEST);
        bar.add(statusRight, BorderLayout.EAST);
        return bar;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 현재 상태에 맞는 탭 점 색. */
    public Color stateColor() {
        return switch (state) {
            case CONNECTED -> Theme.RUNNING_DOT;
            case CONNECTING -> Theme.WARNING;
            case DISCONNECTED -> Theme.TEXT_MUTED;
        };
    }
}
