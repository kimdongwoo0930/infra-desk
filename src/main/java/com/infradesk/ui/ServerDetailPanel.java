package com.infradesk.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.core.Account;
import com.infradesk.core.Server;
import com.infradesk.ui.components.Buttons;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 서버 화면의 "개요" 탭 본문: 4×2 정보 그리드, 메트릭 차트.
 *
 * <p>머리(이름, 배지, 동작 버튼)는 탭을 바꿔도 그대로 있어야 해서 {@link ServerHeader}로 떼어 냈다.
 * 컨테이너 표도 "컨테이너" 탭({@code ContainersTab})으로 옮겼다. 개요 탭에는 7단계에서 컨테이너 요약이 들어온다.
 * 이 패널은 탭 아래의 스크롤되는 영역에 들어간다({@link ServerScreen}).
 */
public class ServerDetailPanel extends JPanel implements javax.swing.Scrollable {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private static final String NONE = "—";

    private final com.infradesk.ui.metrics.MetricsPanel metrics = new com.infradesk.ui.metrics.MetricsPanel();
    private Server server;

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
        setBorder(BorderFactory.createEmptyBorder(20, 28, 24, 28));

        add(infoGrid());
        add(Box.createVerticalStrut(20));
        metrics.setAlignmentX(Component.LEFT_ALIGNMENT);
        metrics.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, metrics.getPreferredSize().height));
        add(metrics);
        add(Box.createVerticalGlue());
    }

    public com.infradesk.ui.metrics.MetricsPanel metrics() {
        return metrics;
    }

    // 스크롤 가능: 뷰포트 너비를 채우고, 컨테이너 표가 길면 세로로 스크롤한다.
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

    public void show(Server server, Account account) {
        boolean sameServer = this.server != null && this.server.id().equals(server.id());
        this.server = server;
        if (ipLabel != null) {
            ipLabel.setText(account.provider().isCloud() ? "공인 IP" : "주소");
        }
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

    private boolean cloud = true;
    private JLabel cpuMemLabel;

    /** SSH로 읽은 정보로 업타임, OS, 부팅 볼륨, 포트를 채운다. */
    public void setFacts(com.infradesk.ssh.HostFacts f) {
        if (!cloud && f.cpuCount() > 0) {
            // 직접 연결 기기에는 클라우드 사양이 없다: 서버가 자신의 크기를 알려준다.
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

    /** SSH 전용 칸 네 개에 같은 짧은 텍스트(예: "SSH 설정 후 표시")를 보여준다. */
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

            /** 불투명한 칸을 잘라내서 바깥쪽 모서리가 둥글게 유지되게 한다. */
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

    /** 공인 IP 칸: 가려진 값, 보기 토글, 복사(실제 주소를 복사한다). */
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
        return cell;
    }

    /** 클라우드 서버는 "공인 IP", 직접 연결 서버는 "주소"(호스트 이름 또는 IP). */
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

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
