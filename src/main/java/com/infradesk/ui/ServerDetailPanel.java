package com.infradesk.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.core.Account;
import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.infradesk.service.ServerAction;
import com.infradesk.ui.components.Buttons;
import com.infradesk.ui.components.StatusBadge;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

/** Right-hand content for the selected server: header, actions, and the 4×2 info grid. */
public class ServerDetailPanel extends JPanel {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private static final String NONE = "—";

    private final JLabel title = new JLabel();
    private final StatusBadge badge = new StatusBadge();
    private final JLabel subtitle = new JLabel();
    private final JButton sshButton = Buttons.primary("SSH 열기", "terminal");
    private final JButton rebootButton = Buttons.secondary("재부팅", "refresh");
    private final JButton stopButton = Buttons.danger("정지", "stop");
    private final JButton startButton = Buttons.secondary("시작", "play");
    private final JLabel actionStatus = new JLabel(" ");
    private Consumer<ServerAction> onAction = a -> { };
    private Runnable onSsh = () -> { };
    private Server server;
    private boolean busy;

    private final JLabel cpuMem = value(false);
    private final JLabel publicIp = value(true);
    private final JLabel uptime = value(false);
    private final JLabel os = value(false);
    private final JLabel bootVolume = value(false);
    private final JLabel privateIp = value(true);
    private final JLabel ports = value(false);
    private final JLabel created = value(false);

    public ServerDetailPanel() {
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));

        add(header());
        add(Box.createVerticalStrut(16));
        add(infoGrid());
        add(Box.createVerticalGlue());

        sshButton.addActionListener(e -> onSsh.run());
        startButton.addActionListener(e -> onAction.accept(ServerAction.START));
        stopButton.addActionListener(e -> onAction.accept(ServerAction.STOP));
        rebootButton.addActionListener(e -> onAction.accept(ServerAction.REBOOT));
    }

    public void onSsh(Runnable listener) {
        this.onSsh = listener;
    }

    public void onAction(Consumer<ServerAction> listener) {
        this.onAction = listener;
    }

    /** Disables the power buttons while a request is in flight and shows a short message. */
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

    private void updateButtons() {
        ServerStatus status = server == null ? ServerStatus.UNKNOWN : server.status();
        boolean stopped = status == ServerStatus.STOPPED;
        startButton.setVisible(stopped);
        stopButton.setVisible(!stopped);
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

    public void show(Server server, Account account) {
        boolean sameServer = this.server != null && this.server.id().equals(server.id());
        this.server = server;
        if (!sameServer) {
            busy = false;
            actionStatus.setText(" ");
        } else if (!server.status().isTransitional() && !busy) {
            actionStatus.setText(" ");
        }
        updateButtons();
        title.setText(server.name());
        badge.setStatus(server.status());
        subtitle.setText(String.join(" · ", account.displayName(), nz(server.region()), nz(server.shape())));

        cpuMem.setText(server.cpuCount() > 0
                ? trim(server.cpuCount()) + " OCPU · " + trim(server.memoryGb()) + " GB"
                : NONE);
        publicIp.setText(server.publicIpAddress().orElse(NONE));
        privateIp.setText(server.privateIpAddress().orElse(NONE));
        created.setText(server.createdAt() == null ? NONE : DATE.format(server.createdAt()));
        // Filled over SSH in later stages.
        uptime.setText(NONE);
        os.setText(NONE);
        bootVolume.setText(NONE);
        ports.setText(NONE);
    }

    private JPanel header() {
        JPanel header = new JPanel(new BorderLayout(16, 0));
        header.setOpaque(false);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        JPanel titleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        titleRow.setOpaque(false);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        titleRow.add(title);
        titleRow.add(Box.createHorizontalStrut(10));
        titleRow.add(badge);
        titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        subtitle.setForeground(Theme.TEXT_MUTED);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        left.add(titleRow);
        left.add(Box.createVerticalStrut(6));
        left.add(subtitle);
        actionStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
        actionStatus.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        left.add(Box.createVerticalStrut(4));
        left.add(actionStatus);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(sshButton);
        actions.add(rebootButton);
        actions.add(stopButton);
        actions.add(startButton);

        header.add(left, BorderLayout.CENTER);
        header.add(actions, BorderLayout.EAST);
        header.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, header.getPreferredSize().height));
        return header;
    }

    private JPanel infoGrid() {
        JPanel grid = new JPanel(new GridLayout(2, 4, 1, 1)) {
            @Override
            protected void paintComponent(java.awt.Graphics g) {
                var g2 = (java.awt.Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Theme.DIVIDER);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), Theme.ARC_CARD * 2, Theme.ARC_CARD * 2);
                g2.dispose();
            }

            /** Clip the opaque cells so the outer corners stay rounded. */
            @Override
            protected void paintChildren(java.awt.Graphics g) {
                var g2 = (java.awt.Graphics2D) g.create();
                g2.clip(new java.awt.geom.RoundRectangle2D.Float(1, 1, getWidth() - 2, getHeight() - 2,
                        Theme.ARC_CARD * 2 - 2, Theme.ARC_CARD * 2 - 2));
                super.paintChildren(g2);
                g2.dispose();
            }
        };
        grid.setOpaque(false);
        grid.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        grid.add(cell("OCPU / 메모리", cpuMem));
        grid.add(cell("공인 IP", publicIp));
        grid.add(cell("업타임", uptime));
        grid.add(cell("OS", os));
        grid.add(cell("부트 볼륨", bootVolume));
        grid.add(cell("사설 IP", privateIp));
        grid.add(cell("열린 포트", ports));
        grid.add(cell("생성일", created));
        grid.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, grid.getPreferredSize().height));
        return grid;
    }

    private static JPanel cell(String label, JLabel value) {
        JPanel cell = new JPanel();
        cell.setBackground(Theme.PANEL_BG);
        cell.setLayout(new BoxLayout(cell, BoxLayout.Y_AXIS));
        cell.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        JLabel l = new JLabel(label);
        l.setForeground(Theme.TEXT_MUTED);
        l.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        cell.add(l);
        cell.add(Box.createVerticalStrut(4));
        cell.add(value);
        return cell;
    }

    private static JLabel value(boolean mono) {
        JLabel l = new JLabel(NONE);
        if (mono) {
            l.setFont(Theme.monoFont(14f));
        } else {
            l.putClientProperty(FlatClientProperties.STYLE, "font: +2 $medium.font");
        }
        return l;
    }

    private static String nz(String s) {
        return s == null ? NONE : s;
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
