package com.infradesk.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.core.Account;
import com.infradesk.core.Server;
import com.infradesk.ui.components.Buttons;
import com.infradesk.ui.components.RoundedPanel;
import com.infradesk.ui.components.WrapLayout;
import com.infradesk.ui.overview.AttentionCard;
import com.infradesk.ui.overview.ContainerSummaryCard;
import com.infradesk.ui.overview.RecentActivityCard;
import com.infradesk.ui.overview.SavedCommandsCard;
import com.infradesk.ui.overview.StatusSection;
import com.infradesk.ui.overview.TwoColumnLayout;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 서버 화면의 "개요" 탭 본문 (디자인 v2). 두 열이다.
 *
 * <pre>
 *  왼쪽(남는 폭)                          오른쪽(폭 300)
 *  ┌ 지금 상태: CPU 메모리 네트워크 디스크   ┌ 확인 필요(멈춘 컨테이너가 있을 때만)
 *  └ 컨테이너 요약                           ├ 정보
 *                                             ├ 저장된 명령어
 *                                             └ 최근 활동(준비 중)
 * </pre>
 *
 * <p>이 탭은 서버에 따로 묻지 않는다. 값은 다른 탭이 읽은 것을 받아 보여 준다: 지금 상태는 모니터링 탭과 서버 정보,
 * 컨테이너 요약과 확인 필요는 컨테이너 탭의 목록, 정보는 서버 정보다. 창이 좁으면 오른쪽 열이 왼쪽 아래로 내려간다.
 * 머리(이름, 배지, 동작 버튼)는 탭을 바꿔도 그대로 있어야 해서 {@link ServerHeader}로 떼어 냈다. 이 패널은 탭
 * 아래의 스크롤되는 영역에 들어간다({@link ServerScreen}).
 */
public class ServerDetailPanel extends JPanel implements javax.swing.Scrollable {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private static final String NONE = "—";
    private static final int PORT_CHIPS = 8;

    private Server server;
    private boolean cloud = true;

    // 왼쪽 열
    private final StatusSection status = new StatusSection();
    private final ContainerSummaryCard containerSummary = new ContainerSummaryCard();
    // 오른쪽 열
    private final AttentionCard attention = new AttentionCard();
    private final Component attentionGap = Box.createVerticalStrut(16);
    private final SavedCommandsCard savedCommands = new SavedCommandsCard();

    // 정보 카드의 값들
    private final JLabel publicIp = value(true);
    private final JLabel ipLabel = new JLabel("공인 IP");
    private final JButton revealIp = Buttons.icon("eye", "공인 IP 보기", 22);
    private final JButton copyIp = Buttons.icon("copy", "공인 IP 복사", 22);
    private String publicIpValue;
    private final JLabel privateIp = value(true);
    private final JLabel os = value(false);
    private final JLabel uptime = value(false);
    /** 열린 포트 칩들. 포트가 많으면 다음 줄로 넘어가고 칸 높이가 따라 늘어난다. */
    private final JPanel portsBox = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 6, true));
    private final JLabel sshUser = value(true);
    private final JLabel created = value(false);
    private Runnable onSshSettings = () -> { };

    public ServerDetailPanel() {
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(20, 28, 24, 28));
        setLayout(new TwoColumnLayout());

        JPanel left = column();
        left.add(status);
        left.add(Box.createVerticalStrut(16));
        left.add(containerSummary);

        JPanel right = column();
        right.add(attention);
        right.add(attentionGap);
        right.add(infoCard());
        right.add(Box.createVerticalStrut(16));
        right.add(savedCommands);
        right.add(Box.createVerticalStrut(16));
        right.add(new RecentActivityCard());
        attentionGap.setVisible(false);
        attention.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentShown(java.awt.event.ComponentEvent e) {
                attentionGap.setVisible(true);
            }

            @Override
            public void componentHidden(java.awt.event.ComponentEvent e) {
                attentionGap.setVisible(false);
            }
        });

        add(left);
        add(right);
    }

    private static JPanel column() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        return p;
    }

    // ── 쓰는 쪽이 값을 넣고 이벤트를 거는 곳 ────────────────────

    /** "지금 상태"의 값을 받는 쪽과, 칸을 눌렀을 때 할 일을 걸 곳. */
    public StatusSection statusSection() {
        return status;
    }

    /** 컨테이너 요약: 목록을 넣고, 행의 작업을 눌렀을 때 할 일을 걸 곳. */
    public ContainerSummaryCard containerSummary() {
        return containerSummary;
    }

    /** 확인 필요 카드(멈춘 컨테이너). */
    public AttentionCard attention() {
        return attention;
    }

    /** 저장된 명령어 카드. */
    public SavedCommandsCard savedCommands() {
        return savedCommands;
    }

    /** 정보 카드의 [SSH 설정]을 눌렀을 때. */
    public void onSshSettings(Runnable listener) {
        this.onSshSettings = listener;
    }

    /** 정보 카드의 SSH 줄: 저장된 SSH 설정의 사용자 이름. 설정이 없으면 null. */
    public void setSshUser(String user) {
        sshUser.setText(user == null || user.isBlank() ? "설정 안 됨" : user);
        sshUser.setForeground(user == null || user.isBlank() ? Theme.TEXT_MUTED : Theme.TEXT);
    }

    // 스크롤 가능: 뷰포트 너비를 채우고, 내용이 길면 세로로 스크롤한다.
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
        return r.height;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }

    // ── 서버 ────────────────────────────────────────────────────

    public void show(Server server, Account account) {
        boolean sameServer = this.server != null && this.server.id().equals(server.id());
        this.server = server;
        cloud = account.provider().isCloud();
        ipLabel.setText(cloud ? "공인 IP" : "주소");
        status.setServer(server, cloud);

        publicIpValue = server.publicIpAddress().orElse(null);
        renderPublicIp();
        privateIp.setText(server.privateIpAddress().orElse(NONE));
        created.setText(server.createdAt() == null ? NONE : DATE.format(server.createdAt()));
        if (!sameServer) {
            setFactsMessage(NONE);
            setSshUser(null);
        }
    }

    /** SSH로 읽은 정보로 업타임, OS, 포트와 "지금 상태"의 디스크 칸을 채운다. */
    public void setFacts(com.infradesk.ssh.HostFacts f) {
        status.setFacts(f);
        uptime.setText(f.uptime() == null ? NONE : formatUptime(f.uptime()));
        uptime.setToolTipText(null);
        os.setText(f.osName() == null ? NONE : f.osName());
        os.setToolTipText(f.osName());
        showPorts(f.ports());
        for (JLabel l : new JLabel[] {uptime, os}) {
            l.setForeground(Theme.TEXT);
        }
    }

    /** SSH 전용 칸에 같은 짧은 텍스트(예: "SSH 설정 후 표시")를 보여준다. */
    public void setFactsMessage(String message) {
        status.setFactsMessage(message);
        for (JLabel l : new JLabel[] {uptime, os}) {
            l.setText(message);
            l.setToolTipText(message.equals(NONE) ? null : message);
            l.setForeground(message.equals(NONE) ? Theme.TEXT : Theme.TEXT_MUTED);
        }
        portsBox.removeAll();
        JLabel text = new JLabel(message);
        text.setForeground(message.equals(NONE) ? Theme.TEXT : Theme.TEXT_MUTED);
        portsBox.add(text);
        portsBox.revalidate();
        portsBox.repaint();
    }

    /** 열린 포트를 칩으로: 앞의 {@value #PORT_CHIPS}개와 나머지 개수. 없으면 "없음". */
    private void showPorts(java.util.List<Integer> ports) {
        portsBox.removeAll();
        if (ports.isEmpty()) {
            portsBox.add(new JLabel("없음"));
        }
        for (int p : ports.subList(0, Math.min(PORT_CHIPS, ports.size()))) {
            portsBox.add(portChip(String.valueOf(p)));
        }
        if (ports.size() > PORT_CHIPS) {
            JLabel more = new JLabel("외 " + (ports.size() - PORT_CHIPS) + "개");
            more.setForeground(Theme.TEXT_MUTED);
            portsBox.add(more);
        }
        portsBox.setToolTipText(ports.isEmpty() ? null : "외부에서 접속을 받는 TCP 포트 (localhost 전용 제외): "
                + String.join(", ", ports.stream().map(String::valueOf).toList()));
        portsBox.revalidate();
        portsBox.repaint();
    }

    private static JPanel portChip(String port) {
        RoundedPanel chip = new RoundedPanel(new BorderLayout(), new java.awt.Color(0x3A3D42), null, 5);
        chip.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        JLabel label = new JLabel(port);
        label.setFont(Theme.monoFont(12f));
        chip.add(label);
        return chip;
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

    // ── 정보 카드 ───────────────────────────────────────────────

    private JPanel infoCard() {
        RoundedPanel card = new RoundedPanel(new BorderLayout(0, 12), Theme.PANEL_BG, Theme.DIVIDER, Theme.ARC_CARD);
        card.setAlignmentX(LEFT_ALIGNMENT);
        card.setBorder(BorderFactory.createEmptyBorder(14, 16, 16, 16));

        JLabel title = new JLabel("정보");
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 15f));
        JButton sshSettings = Buttons.ghost("SSH 설정");
        sshSettings.setForeground(Theme.TEXT_MUTED);
        sshSettings.addActionListener(e -> onSshSettings.run());
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(title, BorderLayout.WEST);
        head.add(sshSettings, BorderLayout.EAST);

        JPanel rows = new JPanel();
        rows.setOpaque(false);
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.add(infoRow(ipLabel, ipValue()));
        rows.add(infoRow("사설 IP", privateIp));
        rows.add(infoRow("OS", os));
        rows.add(infoRow("업타임", uptime));
        portsBox.setOpaque(false);
        rows.add(infoRow("열린 포트", portsBox));
        rows.add(infoRow("SSH", sshUser));
        rows.add(infoRow("생성일", created));
        card.add(head, BorderLayout.NORTH);
        card.add(rows, BorderLayout.CENTER);
        return card;
    }

    private static JPanel infoRow(String label, Component value) {
        return infoRow(new JLabel(label), value);
    }

    /** 왼쪽에 흐린 이름(폭 64), 오른쪽에 값. 값이 길면 "…"로 끝난다. */
    private static JPanel infoRow(JLabel label, Component value) {
        label.setForeground(Theme.TEXT_MUTED);
        label.setPreferredSize(new Dimension(64, 28));
        // 값이 여러 줄이 되어도 이름은 첫 줄 높이에 붙어 있게 한다.
        JPanel name = new JPanel(new BorderLayout());
        name.setOpaque(false);
        name.add(label, BorderLayout.NORTH);
        JPanel row = new JPanel(new BorderLayout(10, 0)) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
        if (value instanceof JLabel v) {
            v.setMinimumSize(new Dimension(0, 24));
        }
        row.add(name, BorderLayout.WEST);
        row.add(value, BorderLayout.CENTER);
        return row;
    }

    /** 공인 IP 값: 가려진 값, 보기 토글, 복사(실제 주소를 복사한다). */
    private JPanel ipValue() {
        for (JButton b : new JButton[] {revealIp, copyIp}) {
            b.setIcon(Icons.get(b == revealIp ? "eye" : "copy", 14));
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
        row.add(publicIp, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.setOpaque(false);
        buttons.add(revealIp);
        buttons.add(copyIp);
        row.add(buttons, BorderLayout.EAST);
        IpPrivacy.onChange(this::renderPublicIp);
        return row;
    }

    private void renderPublicIp() {
        publicIp.setText(publicIpValue == null ? NONE : IpPrivacy.display(publicIpValue));
        boolean revealed = IpPrivacy.isRevealed();
        revealIp.setIcon(Icons.get(revealed ? "eye-off" : "eye", 14));
        String label = revealed ? "공인 IP 숨기기" : "공인 IP 보기";
        revealIp.setToolTipText(label);
        revealIp.getAccessibleContext().setAccessibleName(label);
        revealIp.setVisible(publicIpValue != null);
        copyIp.setVisible(publicIpValue != null);
        copyIp.setToolTipText("공인 IP 복사");
    }

    private static JLabel value(boolean mono) {
        JLabel l = new JLabel(NONE);
        if (mono) {
            l.setFont(Theme.monoFont(13f));
        } else {
            l.putClientProperty(FlatClientProperties.STYLE, "font: +0 $medium.font");
        }
        return l;
    }
}
