package com.infradesk.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.core.Account;
import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.infradesk.service.ServerAction;
import com.infradesk.ui.components.Buttons;
import com.infradesk.ui.components.ComingSoon;
import com.infradesk.ui.components.StatusBadge;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.function.Consumer;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 서버 화면의 머리 (디자인 v2): 즐겨찾기 별, 서버 이름, 상태 배지, 계정·리전·shape 한 줄,
 * 그리고 오른쪽의 동작 버튼([SSH 열기] [재부팅] [정지/시작] [⋯]).
 *
 * <p>예전에는 {@link ServerDetailPanel} 안에 있던 부분이다. 탭이 생기면서 머리는 탭을 바꿔도 그대로
 * 있어야 하고 본문만 바뀌어야 해서 따로 떼어 냈다. 버튼을 눌렀을 때 무엇을 할지는 이 클래스가 모른다 —
 * {@link #onSsh}, {@link #onAction}으로 받은 동작을 부를 뿐이고, 실제 일은 {@code MainFrame}이 한다.
 */
public class ServerHeader extends JPanel {

    /** 즐겨찾기 별 버튼 크기. */
    private static final int STAR_SIZE = 26;
    /** [⋯] 버튼은 시안대로 34×34 정사각형. */
    private static final int MORE_SIZE = 34;

    private final JButton favorite = ComingSoon.apply(Buttons.icon("star", "즐겨찾기", STAR_SIZE)); // 준비 중(v2): 즐겨찾기 저장소가 없다
    private final JLabel title = new JLabel();
    private final StatusBadge badge = new StatusBadge();
    private final JLabel subtitle = new JLabel();
    private final JLabel actionStatus = new JLabel(" ");
    private final JButton sshButton = Buttons.primary("SSH 열기", "terminal");
    private final JButton rebootButton = Buttons.secondary("재부팅", "refresh");
    private final JButton stopButton = Buttons.danger("정지", "stop");
    private final JButton startButton = Buttons.secondary("시작", "play");
    private final JButton moreButton = moreButton();

    private Consumer<ServerAction> onAction = a -> { };
    private Runnable onSsh = () -> { };
    private Server server;
    private boolean busy;
    /** 앱이 전원을 켜거나 끌 수 없는 직접 연결 기기는 false. */
    private boolean powerControl = true;
    /** IP를 가리기 전의 원래 부제. IP 가림을 켜고 끌 때 다시 가려서 보여 주려고 보관한다. */
    private String subtitleText = "";

    public ServerHeader() {
        super(new BorderLayout(16, 0));
        setOpaque(false);

        // 왼쪽: [별] 이름 [배지] / 부제 / 동작 결과 한 줄
        JPanel titleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        titleRow.setOpaque(false);
        titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        titleRow.add(favorite);
        titleRow.add(Box.createHorizontalStrut(6));
        titleRow.add(title);
        titleRow.add(Box.createHorizontalStrut(10));
        titleRow.add(badge);

        subtitle.setForeground(Theme.TEXT_MUTED);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        actionStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
        actionStatus.putClientProperty(FlatClientProperties.STYLE, "font: -1");

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.add(titleRow);
        left.add(Box.createVerticalStrut(6));
        left.add(subtitle);
        left.add(Box.createVerticalStrut(4));
        left.add(actionStatus);

        // 오른쪽: 동작 버튼. 정지와 시작은 서버 상태에 따라 하나만 보인다(updateButtons).
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(sshButton);
        actions.add(rebootButton);
        actions.add(stopButton);
        actions.add(startButton);
        actions.add(moreButton);

        add(left, BorderLayout.CENTER);
        add(actions, BorderLayout.EAST);

        sshButton.addActionListener(e -> onSsh.run());
        startButton.addActionListener(e -> onAction.accept(ServerAction.START));
        stopButton.addActionListener(e -> onAction.accept(ServerAction.STOP));
        rebootButton.addActionListener(e -> onAction.accept(ServerAction.REBOOT));

        // 공인 IP 가리기를 켜고 끄면 부제 속 IP도 같이 바뀐다.
        IpPrivacy.onChange(() -> subtitle.setText(IpPrivacy.mask(subtitleText)));
    }

    /** [⋯] 더보기 버튼: 정사각형. 메뉴에 들어갈 동작이 정해지지 않아 준비 중이다. */
    private static JButton moreButton() {
        JButton b = Buttons.secondary("", "more"); // 준비 중(v2): [⋯] 메뉴의 내용이 아직 없다
        b.setPreferredSize(new Dimension(MORE_SIZE, MORE_SIZE));
        b.getAccessibleContext().setAccessibleName("작업 더 보기");
        return ComingSoon.apply(b);
    }

    public void onSsh(Runnable listener) {
        this.onSsh = listener;
    }

    public void onAction(Consumer<ServerAction> listener) {
        this.onAction = listener;
    }

    /** 요청이 진행 중인 동안 전원 버튼을 비활성화하고 짧은 메시지를 보여준다. */
    public void setBusy(boolean busy, String message) {
        this.busy = busy;
        actionStatus.setText(message == null || message.isEmpty() ? " " : message);
        actionStatus.setForeground(Theme.TEXT_SECONDARY);
        updateButtons();
    }

    public void showActionError(String message) {
        this.busy = false;
        actionStatus.setText(message);
        actionStatus.setForeground(Theme.DANGER_TEXT);
        updateButtons();
    }

    /** 서버의 상태에 맞춰 어떤 버튼이 보이고 눌리는지 정한다. */
    private void updateButtons() {
        ServerStatus status = server == null ? ServerStatus.UNKNOWN : server.status();
        boolean stopped = status == ServerStatus.STOPPED;
        startButton.setVisible(powerControl && stopped);
        stopButton.setVisible(powerControl && !stopped);
        rebootButton.setVisible(powerControl);
        startButton.setEnabled(!busy && status.canStart());
        stopButton.setEnabled(!busy && status.canStop());
        rebootButton.setEnabled(!busy && status.canReboot());
        sshButton.setEnabled(status == ServerStatus.RUNNING);
        sshButton.setToolTipText(status == ServerStatus.RUNNING ? null : "서버가 실행 중일 때 연결할 수 있어요");
        String reason = busy ? "요청을 보내는 중이에요"
                : status.isTransitional() ? status.label() + "이에요. 끝나면 다시 누를 수 있어요" : null;
        for (JButton b : new JButton[] {startButton, stopButton, rebootButton}) {
            b.setToolTipText(b.isEnabled() ? null : reason);
        }
    }

    /**
     * 선택한 서버를 머리에 보여 준다. 다른 서버로 바뀌면 이전 서버의 "요청 중" 표시를 지운다.
     * 같은 서버를 주기적으로 다시 보여 주는 경우에는, 상태가 안정되었고 요청 중이 아닐 때만 그 표시를 지운다.
     */
    public void show(Server server, Account account) {
        boolean sameServer = this.server != null && this.server.id().equals(server.id());
        this.server = server;
        this.powerControl = account.provider().hasPowerControl();
        if (!sameServer) {
            busy = false;
            actionStatus.setText(" ");
        } else if (!server.status().isTransitional() && !busy) {
            actionStatus.setText(" ");
        }
        updateButtons();
        title.setText(server.name());
        badge.setStatus(server.status());
        if (!account.provider().isCloud()) {
            // 직접 연결 기기의 주소는 부제에 그대로 들어가므로, 가림 대상으로 먼저 등록한다.
            IpPrivacy.protect(server.publicIp());
        }
        subtitleText = account.provider().isCloud()
                ? String.join(" · ", account.displayName(), nz(server.region()), nz(server.shape()))
                : "직접 연결 (SSH) · " + nz(server.shape());
        subtitle.setText(IpPrivacy.mask(subtitleText));
    }

    private static String nz(String s) {
        return s == null ? "—" : s;
    }
}
