package com.infradesk.ui.metrics;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.alert.AlertSettings;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;
import com.infradesk.ui.components.Meter;
import com.infradesk.ui.components.RoundedPanel;
import com.infradesk.ui.components.WrappingLabel;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 모니터링 탭의 오른쪽 열 (디자인 v2): "이 기간 요약", "디스크 (/)", "알림 기준" 카드 셋.
 *
 * <p>이 열은 서버에 묻지 않는다. 요약은 차트가 그린 점들로 계산한 글자를 받고, 디스크는 서버 정보를 읽을 때
 * 같이 얻은 값을 받고, 알림 기준은 알림 설정을 받아서 보여 줄 뿐이다.
 */
final class MetricsSidePanel extends JPanel {

    /** 오른쪽 열의 폭. */
    static final int WIDTH = 300;
    private static final int PAD = 16;
    /** 요약 줄에서 값 글자를 접는 폭: 열 폭 - 카드 여백 - 이름 칸(56) - 간격(10). */
    private static final int VALUE_WRAP = WIDTH - PAD * 2 - 56 - 10;
    private static final int BODY_WRAP = WIDTH - PAD * 2;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private final WrappingLabel cpuLine = value("—");
    private final WrappingLabel memoryLine = value("—");
    private final WrappingLabel networkLine = value("—");
    private final JLabel diskPercent = new JLabel("—");
    private final JLabel diskSize = new JLabel(" ");
    private final Meter diskBar = new Meter();
    private final JLabel diskNote = new JLabel(" ");
    private final WrappingLabel alertBody = new WrappingLabel("", BODY_WRAP, 0, Theme.TEXT);
    private final WrappingLabel alertNote = new WrappingLabel("", BODY_WRAP, -1, Theme.TEXT_MUTED);
    private final JButton alertButton = Buttons.secondary("설정에서 바꾸기", null);
    private Runnable onOpenSettings = () -> { };

    MetricsSidePanel() {
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setPreferredSize(new Dimension(WIDTH, 0));
        setMinimumSize(new Dimension(WIDTH, 0));

        add(summaryCard());
        add(Box.createVerticalStrut(12));
        add(diskCard());
        add(Box.createVerticalStrut(12));
        add(alertCard());
        add(Box.createVerticalGlue());
        alertButton.addActionListener(e -> onOpenSettings.run());
        setDiskMessage("—");
    }

    // ── 카드 조립 ───────────────────────────────────────────────

    private RoundedPanel card() {
        // 세로로 쌓는 BoxLayout은 남는 높이를 카드에 나눠 준다. 카드가 내용보다 길게 늘어나지 않도록 최대 높이를
        // 내용 높이로 묶는다(알림 문구가 줄바꿈으로 길어져도 따라간다).
        RoundedPanel card = new RoundedPanel(new BorderLayout(0, 10), Theme.PANEL_BG, Theme.DIVIDER, Theme.ARC_CARD) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        card.setBorder(BorderFactory.createEmptyBorder(PAD - 2, PAD, PAD - 2, PAD));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        return card;
    }

    private static JLabel title(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 14f));
        return l;
    }

    private static WrappingLabel value(String text) {
        return new WrappingLabel(text, VALUE_WRAP, 0, Theme.TEXT);
    }

    private JPanel summaryCard() {
        RoundedPanel card = card();
        JPanel rows = new JPanel();
        rows.setOpaque(false);
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.add(row("CPU", cpuLine));
        rows.add(Box.createVerticalStrut(8));
        rows.add(row("메모리", memoryLine));
        rows.add(Box.createVerticalStrut(8));
        rows.add(row("네트워크", networkLine));
        card.add(title("이 기간 요약"), BorderLayout.NORTH);
        card.add(rows, BorderLayout.CENTER);
        return card;
    }

    private static JPanel row(String name, WrappingLabel value) {
        JLabel label = new JLabel(name);
        label.setForeground(Theme.TEXT_MUTED);
        label.setPreferredSize(new Dimension(56, label.getPreferredSize().height));
        label.setVerticalAlignment(javax.swing.SwingConstants.TOP);
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(label, BorderLayout.WEST);
        row.add(value, BorderLayout.CENTER);
        return row;
    }

    private JPanel diskCard() {
        RoundedPanel card = card();
        diskPercent.setFont(diskPercent.getFont().deriveFont(Font.BOLD, 22f));
        diskSize.setForeground(Theme.TEXT_MUTED);
        JPanel numbers = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        numbers.setOpaque(false);
        numbers.add(diskPercent);
        numbers.add(diskSize);
        diskNote.setForeground(Theme.TEXT_MUTED);
        diskNote.putClientProperty(FlatClientProperties.STYLE, "font: -1");

        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.setOpaque(false);
        body.add(numbers, BorderLayout.NORTH);
        body.add(diskBar, BorderLayout.CENTER);
        body.add(diskNote, BorderLayout.SOUTH);
        card.add(title("디스크 (/)"), BorderLayout.NORTH);
        card.add(body, BorderLayout.CENTER);
        return card;
    }

    private JPanel alertCard() {
        RoundedPanel card = card();
        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        alertBody.setAlignmentX(Component.LEFT_ALIGNMENT);
        alertNote.setAlignmentX(Component.LEFT_ALIGNMENT);
        alertButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(alertBody);
        body.add(Box.createVerticalStrut(6));
        body.add(alertNote);
        body.add(Box.createVerticalStrut(12));
        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttonRow.setOpaque(false);
        buttonRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        buttonRow.add(alertButton);
        body.add(buttonRow);
        card.add(title("알림 기준"), BorderLayout.NORTH);
        card.add(body, BorderLayout.CENTER);
        return card;
    }

    // ── 쓰는 쪽이 부르는 메서드 ─────────────────────────────────

    /** 요약 세 줄을 바꾼다. 글자는 {@link MetricSummary}가 만든 것이다. */
    void showSummary(String cpu, String memory, String network) {
        cpuLine.setText(cpu);
        memoryLine.setText(memory);
        networkLine.setText(network);
    }

    /**
     * 디스크 사용량. {@code total}이 0 이하(모름)이면 줄표를 보여 준다. {@code readAt}은 서버에서 읽은 시각이다.
     * 서버 정보는 서버를 고를 때(5분 안에 다시 고르면 저장된 값) 읽으므로 "몇 분마다"라고 적지 않고 읽은 시각만 적는다.
     */
    void setDisk(long used, long total, Instant readAt) {
        if (total <= 0 || used < 0) {
            setDiskMessage("—");
            return;
        }
        double percent = 100.0 * used / total;
        diskPercent.setText(Math.round(percent) + "%");
        diskSize.setText(gb(used) + " / " + gb(total) + " GB");
        diskBar.setPercent(percent);
        diskNote.setText("SSH로 읽은 값 · " + CLOCK.format(readAt) + " 기준");
    }

    /** 디스크 값을 읽지 못했거나 아직 없을 때의 안내("SSH 설정 후 표시", "불러오는 중…" 등). */
    void setDiskMessage(String message) {
        diskPercent.setText("—");
        diskSize.setText(" ");
        diskBar.setPercent(0);
        diskNote.setText("—".equals(message) ? " " : message);
    }

    /** 알림 설정에 맞춰 "알림 기준" 카드의 글자와 버튼을 바꾼다. */
    void setAlert(AlertSettings settings) {
        alertBody.setText(alertText(settings));
        alertNote.setText(cpuThreshold(settings) == null ? "" : "차트의 주황 점선이 이 기준이에요.");
        alertButton.setText(cpuThreshold(settings) == null ? "설정에서 켜기" : "설정에서 바꾸기");
    }

    /** [설정에서 바꾸기]를 눌렀다. */
    void onOpenSettings(Runnable listener) {
        this.onOpenSettings = listener;
    }

    // ── 순수 함수 (테스트용으로 열어 둔다) ──────────────────────

    /**
     * CPU 알림이 켜져 있으면 차트에 점선으로 그릴 기준값(%)을, 꺼져 있으면 null을 돌려준다.
     * 전체 스위치와 "CPU가 높을 때" 종류가 둘 다 켜져 있어야 알림이 나가므로 둘 다 본다.
     */
    static Double cpuThreshold(AlertSettings s) {
        return s.enabled() && s.cpuHigh() ? (double) s.cpuThreshold() : null;
    }

    /** 알림 카드의 본문. 켜져 있으면 기준을, 꺼져 있으면 꺼져 있다고 적는다. */
    static String alertText(AlertSettings s) {
        if (cpuThreshold(s) == null) {
            return "CPU 알림이 꺼져 있어요. 켜 두면 CPU가 기준 이상으로 오래 이어질 때 디스코드로 알려 줘요.";
        }
        return "CPU " + s.cpuThreshold() + "% 이상이 " + s.cpuMinutes() + "분 이상 계속되면 디스코드로 알려요.";
    }

    private static String gb(long bytes) {
        double v = bytes / (1024.0 * 1024 * 1024);
        return v >= 10 ? String.valueOf(Math.round(v)) : String.format(Locale.ROOT, "%.1f", v);
    }

    // ── 테스트에서 화면의 상태를 읽는 데 쓴다 ───────────────────

    /** WrappingLabel은 폭에 맞춰 줄을 접으면서 글자 안에 줄바꿈을 넣는다. 화면에 보이는 문구와 비교하려면 한 줄로 합친다. */
    private static String flat(WrappingLabel label) {
        return label.getText().replaceAll("\\s+", " ").strip();
    }

    String cpuText() {
        return flat(cpuLine);
    }

    String memoryText() {
        return flat(memoryLine);
    }

    String networkText() {
        return flat(networkLine);
    }

    String diskPercentText() {
        return diskPercent.getText();
    }

    String diskSizeText() {
        return diskSize.getText();
    }

    String diskNoteText() {
        return diskNote.getText();
    }

    double diskBarPercent() {
        return diskBar.getPercent();
    }

    String alertBodyText() {
        return flat(alertBody);
    }

    String alertButtonText() {
        return alertButton.getText();
    }

    JButton alertButton() {
        return alertButton;
    }
}
