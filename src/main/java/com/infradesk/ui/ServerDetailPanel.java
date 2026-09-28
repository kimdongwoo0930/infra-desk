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
public class ServerDetailPanel extends JPanel implements javax.swing.Scrollable {

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
    private final com.infradesk.ui.metrics.MetricsPanel metrics = new com.infradesk.ui.metrics.MetricsPanel();
    private final com.infradesk.ui.containers.ContainersPanel containers = new com.infradesk.ui.containers.ContainersPanel();
    private Consumer<ServerAction> onAction = a -> { };
    private Runnable onSsh = () -> { };
    private Server server;
    private boolean busy;

    private final JLabel cpuMem = value(false);
    private final JLabel publicIp = value(true);
    private final JButton revealIp = Buttons.icon("eye", "공인 IP 보기", 22);
    private final JButton copyIp = Buttons.icon("copy", "공인 IP 복사", 22);
    private String publicIpValue;
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
        add(Box.createVerticalStrut(20));
        metrics.setAlignmentX(Component.LEFT_ALIGNMENT);
        metrics.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, metrics.getPreferredSize().height));
        add(metrics);
        add(Box.createVerticalStrut(24));
        containers.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(containers);
        add(Box.createVerticalGlue());

        sshButton.addActionListener(e -> onSsh.run());
        startButton.addActionListener(e -> onAction.accept(ServerAction.START));
        stopButton.addActionListener(e -> onAction.accept(ServerAction.STOP));
        rebootButton.addActionListener(e -> onAction.accept(ServerAction.REBOOT));
    }

    public com.infradesk.ui.metrics.MetricsPanel metrics() {
        return metrics;
    }

    public com.infradesk.ui.containers.ContainersPanel containers() {
        return containers;
    }

    // Scrollable: fill the viewport width, scroll vertically when the containers table is long.
    @Override
    public java.awt.Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(java.awt.Rectangle r, int orientation, int direction) {
        return 24;
    }

    @Override
    public int getScrollableBlockIncrement(java.awt.Rectangle r, int orientation, int direction) {
        return r.height - 48;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return getParent() instanceof javax.swing.JViewport v && v.getHeight() > getPreferredSize().height;
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

    /** False for directly connected machines, which the app can't power on or off. */
    private boolean powerControl = true;

    public void show(Server server, Account account) {
        boolean sameServer = this.server != null && this.server.id().equals(server.id());
        this.server = server;
        this.powerControl = account.provider().hasPowerControl();
        if (ipLabel != null) {
            ipLabel.setText(account.provider().isCloud() ? "공인 IP" : "주소");
        }
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
            IpPrivacy.protect(server.publicIp());
        }
        subtitleText = account.provider().isCloud()
                ? String.join(" · ", account.displayName(), nz(server.region()), nz(server.shape()))
                : "직접 연결 (SSH) · " + nz(server.shape());
        subtitle.setText(IpPrivacy.mask(subtitleText));
        cloud = account.provider().isCloud();
        if (cpuMemLabel != null) {
            cpuMemLabel.setText(cloud ? "OCPU / 메모리" : "CPU / 메모리");
        }

        cpuMem.setText(server.cpuCount() > 0
                ? trim(server.cpuCount()) + " OCPU · " + trim(server.memoryGb()) + " GB"
                : NONE);
        publicIpValue = server.publicIpAddress().orElse(null);
        renderPublicIp();
        privateIp.setText(server.privateIpAddress().orElse(NONE));
        created.setText(server.createdAt() == null ? NONE : DATE.format(server.createdAt()));
        if (!sameServer) {
            setFactsMessage(NONE);
        }
    }

    private String subtitleText = "";
    private boolean cloud = true;
    private JLabel cpuMemLabel;

    /** Fills uptime, OS, boot volume and ports from an SSH read. */
    public void setFacts(com.infradesk.ssh.HostFacts f) {
        if (!cloud && f.cpuCount() > 0) {
            // No cloud shape for a directly connected machine: the server reports its own size.
            cpuMem.setText(f.cpuCount() + "코어 · " + trim(f.memoryKb() / 1024.0 / 1024.0) + " GB");
        }
        uptime.setText(f.uptime() == null ? NONE : formatUptime(f.uptime()));
        uptime.setToolTipText(null);
        os.setText(f.osName() == null ? NONE : f.osName());
        os.setToolTipText(f.osName());
        bootVolume.setText(f.diskTotal() <= 0 ? NONE
                : gb(f.diskUsed()) + " / " + gb(f.diskTotal()) + " GB");
        bootVolume.setToolTipText(f.diskTotal() <= 0 ? null
                : "루트(/) 파일 시스템 사용량 " + Math.round(100.0 * f.diskUsed() / f.diskTotal()) + "%");
        ports.setText(formatPorts(f.ports()));
        ports.setToolTipText(f.ports().isEmpty() ? null : "외부에서 접속을 받는 TCP 포트 (localhost 전용 제외): "
                + String.join(", ", f.ports().stream().map(String::valueOf).toList()));
        for (JLabel l : new JLabel[] {uptime, os, bootVolume, ports}) {
            l.setForeground(Theme.TEXT);
        }
    }

    /** Shows the same short text (e.g. "SSH 설정 후 표시") in the four SSH-only cells. */
    public void setFactsMessage(String message) {
        for (JLabel l : new JLabel[] {uptime, os, bootVolume, ports}) {
            l.setText(message);
            l.setToolTipText(message.equals(NONE) ? null : message);
            l.setForeground(message.equals(NONE) ? Theme.TEXT : Theme.TEXT_MUTED);
        }
    }

    static String formatUptime(java.time.Duration d) {
        long days = d.toDays();
        long hours = d.toHoursPart();
        long minutes = d.toMinutesPart();
        if (days > 0) {
            return days + "일 " + hours + "시간";
        }
        if (hours > 0) {
            return hours + "시간 " + minutes + "분";
        }
        return Math.max(1, minutes) + "분";
    }

    static String formatPorts(java.util.List<Integer> ports) {
        if (ports.isEmpty()) {
            return "없음";
        }
        int shown = Math.min(4, ports.size());
        String text = String.join(", ", ports.subList(0, shown).stream().map(String::valueOf).toList());
        return ports.size() > shown ? text + " 외 " + (ports.size() - shown) + "개" : text;
    }

    private static String gb(long bytes) {
        double v = bytes / (1024.0 * 1024 * 1024);
        return v >= 10 ? String.valueOf(Math.round(v)) : String.format(java.util.Locale.ROOT, "%.1f", v);
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
        JPanel cpuMemCell = cell("OCPU / 메모리", cpuMem);
        cpuMemLabel = (JLabel) cpuMemCell.getComponent(0);
        grid.add(cpuMemCell);
        grid.add(ipCell());
        grid.add(cell("업타임", uptime));
        grid.add(cell("OS", os));
        grid.add(cell("부트 볼륨", bootVolume));
        grid.add(cell("사설 IP", privateIp));
        grid.add(cell("열린 포트", ports));
        grid.add(cell("생성일", created));
        grid.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, grid.getPreferredSize().height));
        return grid;
    }

    /** Public IP cell: masked value, reveal toggle and copy (copies the real address). */
    private JPanel ipCell() {
        JPanel cell = cell("공인 IP", publicIp);
        ipLabel = (JLabel) cell.getComponent(0);
        for (JButton b : new JButton[] {revealIp, copyIp}) {
            b.setIcon(com.infradesk.ui.Icons.get(b == revealIp ? "eye" : "copy", 14));
        }
        revealIp.addActionListener(e -> IpPrivacy.setRevealed(!IpPrivacy.isRevealed()));
        copyIp.addActionListener(e -> {
            if (publicIpValue != null) {
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new java.awt.datatransfer.StringSelection(publicIpValue), null);
                copyIp.setToolTipText("복사했어요");
            }
        });
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        cell.remove(publicIp);
        row.add(publicIp, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.setOpaque(false);
        buttons.add(revealIp);
        buttons.add(copyIp);
        row.add(buttons, BorderLayout.EAST);
        row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 24));
        cell.add(row);
        IpPrivacy.onChange(this::renderPublicIp);
        IpPrivacy.onChange(() -> subtitle.setText(IpPrivacy.mask(subtitleText)));
        return cell;
    }

    /** "공인 IP" for cloud servers, "주소" (host name or IP) for directly connected ones. */
    private JLabel ipLabel;

    private void renderPublicIp() {
        publicIp.setText(publicIpValue == null ? NONE : IpPrivacy.display(publicIpValue));
        boolean revealed = IpPrivacy.isRevealed();
        revealIp.setIcon(com.infradesk.ui.Icons.get(revealed ? "eye-off" : "eye", 14));
        String label = revealed ? "공인 IP 숨기기" : "공인 IP 보기";
        revealIp.setToolTipText(label);
        revealIp.getAccessibleContext().setAccessibleName(label);
        revealIp.setVisible(publicIpValue != null);
        copyIp.setVisible(publicIpValue != null);
        copyIp.setToolTipText("공인 IP 복사");
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
