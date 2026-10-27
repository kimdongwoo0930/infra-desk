package com.infradesk.ui.overview;

import com.infradesk.core.Metrics;
import com.infradesk.core.Metrics.Sample;
import com.infradesk.core.Server;
import com.infradesk.ssh.HostFacts;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;
import com.infradesk.ui.metrics.Formats;
import com.infradesk.ui.metrics.MetricsObserver;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 개요 탭의 "지금 상태" (디자인 v2): CPU, 메모리, 네트워크, 디스크 네 칸. 값은 서버에 새로 묻지 않고 이미 읽은 것을
 * 받아 쓴다. CPU·메모리·네트워크는 모니터링 탭이 읽은 점들({@link MetricsObserver})에서 최근 값과 최근 1시간 그래프를
 * 만들고, 디스크는 서버를 고를 때 SSH로 읽어 둔 서버 정보({@link HostFacts})를 쓴다.
 */
public final class StatusSection extends JPanel implements MetricsObserver {

    /** 칸의 작은 그래프가 보여 주는 기간. 모니터링 탭에서 기간을 바꿔도 여기는 그대로다. */
    static final Duration SPARK_SPAN = Duration.ofHours(1);

    private final StatusCard cpu = new StatusCard("CPU", false);
    private final StatusCard memory = new StatusCard("메모리", false);
    private final StatusCard network = new StatusCard("네트워크", false);
    private final StatusCard disk = new StatusCard("디스크 (/)", true);
    private final JLabel source = new JLabel(" ");
    private Runnable onOpenMonitoring = () -> { };

    private boolean cloud = true;
    private double cpuCount;
    private double memoryGb;

    public StatusSection() {
        super(new BorderLayout(0, 10));
        setOpaque(false);
        setAlignmentX(LEFT_ALIGNMENT);   // 세로로 쌓는 BoxLayout 안에서 다른 부분과 왼쪽 끝을 맞춘다

        JLabel title = new JLabel("지금 상태");
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 16f));
        source.setForeground(Theme.TEXT_MUTED);
        source.setBorder(BorderFactory.createEmptyBorder(2, 12, 0, 0));
        JButton more = Buttons.ghost("모니터링 탭에서 자세히");
        more.setForeground(Theme.ACCENT_TEXT);
        more.addActionListener(e -> onOpenMonitoring.run());
        JPanel left = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        left.setOpaque(false);
        left.add(title);
        left.add(source);
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(left, BorderLayout.CENTER);
        head.add(more, BorderLayout.EAST);

        JPanel cards = new JPanel(new GridLayout(1, 4, 12, 0));
        cards.setOpaque(false);
        for (StatusCard card : new StatusCard[] {cpu, memory, network, disk}) {
            card.onClick(() -> onOpenMonitoring.run());
            cards.add(card);
        }
        add(head, BorderLayout.NORTH);
        add(cards, BorderLayout.CENTER);
        showNothing("—");
    }

    /** 세로로는 내용만큼만 차지한다(BoxLayout이 남는 높이를 카드에 나눠 주면 카드가 길게 늘어난다). */
    @Override
    public java.awt.Dimension getMaximumSize() {
        return new java.awt.Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    /** 카드나 "모니터링 탭에서 자세히"를 눌렀을 때. */
    public void onOpenMonitoring(Runnable listener) {
        this.onOpenMonitoring = listener;
    }

    /** 서버가 바뀌었거나 새로 읽었다: 사양(코어·메모리)을 보조 글자에 쓰려고 기억해 둔다. */
    public void setServer(Server server, boolean cloud) {
        this.cloud = cloud;
        this.cpuCount = server.cpuCount();
        this.memoryGb = server.memoryGb();
    }

    /** SSH로 읽은 서버 정보: 디스크 칸, 그리고 직접 연결 서버의 코어·메모리. */
    public void setFacts(HostFacts f) {
        if (!cloud && f.cpuCount() > 0) {
            cpuCount = f.cpuCount();
            memoryGb = f.memoryKb() / 1024.0 / 1024.0;
        }
        if (f.diskTotal() > 0 && f.diskUsed() >= 0) {
            double percent = 100.0 * f.diskUsed() / f.diskTotal();
            disk.setText(Math.round(percent) + "%", gbText(f.diskUsed()) + " / " + gbText(f.diskTotal()) + " GB");
            disk.setPercent(percent);
        } else {
            disk.setText("—", "");
            disk.setPercent(0);
        }
    }

    /** 디스크 값을 읽지 못했거나 아직 없을 때의 안내("SSH 설정 후 표시", "불러오는 중…" 등). */
    public void setFactsMessage(String message) {
        disk.setText("—", "—".equals(message) ? "" : message);
        disk.setPercent(0);
    }

    // ── 모니터링 탭이 알려 주는 것 ──────────────────────────────

    @Override
    public void onData(Metrics m) {
        setLatest(cpu, m.cpuPercent(), false);
        setLatest(memory, m.memoryPercent(), true);
        List<Sample> total = sumByTime(m.networkInBps(), m.networkOutBps());
        if (total.isEmpty()) {
            network.setText("—", "");
            network.setSpark(List.of(), 0, Theme.CHART_RX);
        } else {
            String[] text = networkText(last(m.networkInBps()), last(m.networkOutBps()));
            network.setText(text[0], text[1]);
            network.setSpark(lastHour(total), 0, Theme.CHART_TX);
        }
    }

    private void setLatest(StatusCard card, List<Sample> series, boolean isMemory) {
        if (series.isEmpty()) {
            card.setText("—", "");
            card.setSpark(List.of(), 100, Theme.CHART_CPU);
            return;
        }
        double now = series.getLast().value();
        card.setText(Formats.percent(now), isMemory ? memorySub(now, memoryGb) : cpuSub(cpuCount, cloud));
        card.setSpark(lastHour(series), 100, isMemory ? Theme.CHART_MEMORY : Theme.CHART_CPU);
    }

    @Override
    public void onEmpty(String message) {
        showNothing(message);
    }

    @Override
    public void onStatus(String text, boolean error) {
        source.setText(text == null || text.isEmpty() ? " " : text);
        source.setForeground(error ? Theme.WARNING : Theme.TEXT_MUTED);
    }

    private void showNothing(String message) {
        String shown = "—".equals(message) ? "" : message;
        for (StatusCard card : new StatusCard[] {cpu, memory, network}) {
            card.setText("—", shown);
            card.setSpark(List.of(), 100, Theme.CHART_CPU);
        }
    }

    // ── 계산 (순수 함수, 테스트용으로 열어 둔다) ────────────────

    private static double last(List<Sample> series) {
        return series.isEmpty() ? 0 : series.getLast().value();
    }

    /** 가장 최근 점에서 1시간 안의 값들(오래된 것부터). 모니터링 탭이 더 긴 기간을 불러와도 최근 1시간만 쓴다. */
    static List<Double> lastHour(List<Sample> series) {
        if (series.isEmpty()) {
            return List.of();
        }
        var cutoff = series.getLast().time().minus(SPARK_SPAN);
        List<Double> values = new ArrayList<>();
        for (Sample s : series) {
            if (!s.time().isBefore(cutoff)) {
                values.add(s.value());
            }
        }
        return values;
    }

    /** 같은 시각의 두 시계열을 더한다(수신 + 송신). 한쪽에만 있는 시각은 그 값만 쓴다. */
    static List<Sample> sumByTime(List<Sample> a, List<Sample> b) {
        Map<java.time.Instant, Double> sum = new java.util.TreeMap<>();
        for (Sample s : a) {
            sum.merge(s.time(), s.value(), Double::sum);
        }
        for (Sample s : b) {
            sum.merge(s.time(), s.value(), Double::sum);
        }
        List<Sample> result = new ArrayList<>();
        for (var e : sum.entrySet()) {
            result.add(new Sample(e.getKey(), e.getValue()));
        }
        return result;
    }

    /** CPU 칸의 보조 글자: "2 OCPU · 최근 1시간". 코어 수를 모르면 "최근 1시간"만. */
    static String cpuSub(double cores, boolean cloud) {
        if (cores <= 0) {
            return "최근 1시간";
        }
        return trim(cores) + (cloud ? " OCPU" : "코어") + " · 최근 1시간";
    }

    /** 메모리 칸의 보조 글자: 쓴 양 / 전체("5.4 / 12 GB"). 전체를 모르면 "최근 1시간". */
    static String memorySub(double percent, double totalGb) {
        if (totalGb <= 0) {
            return "최근 1시간";
        }
        return gbOne(totalGb * percent / 100.0) + " / " + gbOne(totalGb) + " GB";
    }

    /**
     * 네트워크 칸의 글자: {정수·소수 한 자리 단위가 붙은 합계("38.2 KB/s"), "수신 28.3 · 송신 9.9"}.
     * 보조 글자의 숫자는 합계와 같은 단위로 맞춘다.
     */
    static String[] networkText(double in, double out) {
        double total = in + out;
        double divisor = 1;
        while (total / divisor >= 1000 && divisor < 1e9) {
            divisor *= 1000;
        }
        return new String[] {Formats.rate(total), "수신 " + String.format(Locale.ROOT, "%.1f", in / divisor)
                + " · 송신 " + String.format(Locale.ROOT, "%.1f", out / divisor)};
    }

    private static String gbOne(double gb) {
        return gb >= 10 || gb == Math.rint(gb) ? String.valueOf(Math.round(gb)) : String.format(Locale.ROOT, "%.1f", gb);
    }

    private static String gbText(long bytes) {
        return gbOne(bytes / (1024.0 * 1024 * 1024));
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }

    // ── 테스트에서 화면의 상태를 읽는 데 쓴다 ───────────────────

    String cpuValue() {
        return cpu.valueText();
    }

    String cpuSubText() {
        return cpu.subText();
    }

    String memoryValue() {
        return memory.valueText();
    }

    String memorySubText() {
        return memory.subText();
    }

    String networkValue() {
        return network.valueText();
    }

    String networkSubText() {
        return network.subText();
    }

    String diskValue() {
        return disk.valueText();
    }

    String diskSubText() {
        return disk.subText();
    }

    String sourceText() {
        return source.getText();
    }
}
