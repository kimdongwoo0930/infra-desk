package com.infradesk.ui.metrics;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.alert.AlertSettings;
import com.infradesk.core.Metrics;
import com.infradesk.ssh.ProcStats;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.ComingSoon;
import com.infradesk.ui.components.SegmentedControl;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 서버 화면의 "모니터링" 탭 본문 (디자인 v2): 기간 선택(실시간 · 1시간 · 6시간 · 24시간 · 7일) 아래에 CPU, 메모리,
 * 네트워크의 큰 차트가 세로로 쌓인다.
 *
 * <pre>
 *  [● 실시간 | 1시간 | 6시간 | 24시간 | 7일]  OCI 모니터링 · 1분 간격 · 15:31 갱신            [ ] 활동 표시
 *  ┌ CPU 27% ─────────────────────────┐
 *  │ (큰 차트: 휠 확대 · 끌어서 이동 · 두 번 눌러 초기화)  │
 *  └──────────────────────────────────┘
 *  ... 메모리, 네트워크
 * </pre>
 *
 * <p>"실시간"은 구간 선택의 첫 칸이다. 고르면 예전의 "실시간(SSH)" 토글을 켠 것과 같고({@link #onLiveToggle}),
 * 다른 기간을 고르면 실시간을 끄고 그 기간을 읽는다({@link #onRangeChanged}).
 *
 * <p>이 패널은 서버에 접속하지 않는다. 읽는 일은 {@code MainFrame}이 하고 결과를 {@link #showHistory},
 * {@link #addLive}로 건넨다.
 */
public class MetricsPanel extends JPanel {

    /** 구간 선택의 칸 번호. 0번이 실시간이고 나머지가 기록(과거) 기간이다. */
    private static final int LIVE = 0;
    private static final int DEFAULT_RANGE = 1;
    private static final List<String> LABELS = List.of("실시간", "1시간", "6시간", "24시간", "7일");
    /** 칸별 기간. 실시간(0번)은 기간이 없어서 null이다. */
    private static final List<Duration> RANGES =
            Arrays.asList(null, Duration.ofHours(1), Duration.ofHours(6), Duration.ofHours(24), Duration.ofDays(7));
    /** OCI Monitoring이 기간별로 쓰는 간격(provider/oracle/OracleMetrics.intervalFor와 같다). */
    private static final List<String> INTERVALS = List.of("", "1분", "1분", "5분", "15분");
    private static final String DIRECT_ONLY = "직접 연결 서버는 앱이 켜져 있는 동안 모은 기록만 있어요";
    private static final String HINT = "휠로 확대·축소, 끌어서 이동, 두 번 눌러 처음 범위 · 세 차트가 같은 시점을 함께 가리켜요";
    /** 실시간 샘플은 5분어치를 보관한다. */
    private static final int LIVE_CAPACITY = 5 * 60 / ProcStats.INTERVAL_SECONDS;
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter SECOND = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final SegmentedControl ranges = new SegmentedControl(LABELS);
    private final JLabel status = new JLabel(" ");
    private final MetricChartCard cpu = new MetricChartCard(MetricKind.CPU);
    private final MetricChartCard memory = new MetricChartCard(MetricKind.MEMORY);
    private final MetricChartCard network = new MetricChartCard(MetricKind.NETWORK);
    private final MetricsSidePanel side = new MetricsSidePanel();
    private final List<ProcStats.Sample> liveSamples = new ArrayList<>();
    /** 실시간이 스스로 꺼지기까지 남은 시간을 알려 주는 쪽(MainFrame의 LiveController). 없으면 문구에서 뺀다. */
    private java.util.function.Supplier<java.util.Optional<Duration>> liveRemaining = java.util.Optional::empty;

    private Consumer<Boolean> onLiveToggle = on -> { };
    private Runnable onRangeChanged = () -> { };
    /** 마지막으로 고른 기록 기간의 칸 번호(1~4). 실시간을 보는 동안에도 남아서, 실시간을 끄면 이 기간으로 돌아간다. */
    private int rangeIndex = DEFAULT_RANGE;
    private boolean liveMode;
    /** 직접 연결 서버처럼 1시간 외의 기간이 없는지(false). */
    private boolean historyRangesAvailable = true;
    /** 코드가 선택을 바꾸는 중이면 true. 사용자가 고른 것과 구별해서 리스너를 부르지 않으려는 것이다. */
    private boolean suppress;

    public MetricsPanel() {
        super(new BorderLayout(0, 14));
        setOpaque(false);

        ranges.setDot(LIVE, Theme.RUNNING_DOT);   // 시안: "● 실시간"
        ranges.setSelectedIndex(DEFAULT_RANGE);
        ranges.addSelectionListener(this::segmentChosen);
        status.setForeground(Theme.TEXT_MUTED);
        status.putClientProperty(FlatClientProperties.STYLE, "font: -1");

        // 준비 중(v2): 활동 표시 — 활동 기록이 아직 없어서, 체크해도 차트에 활동 선과 "nginx 재시작" 같은 표시를 그릴 수 없다
        JCheckBox activity = ComingSoon.apply(new JCheckBox("활동 표시"));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 0));
        left.setOpaque(false);
        left.add(ranges);
        left.add(status);
        JPanel controls = new JPanel(new BorderLayout());
        controls.setOpaque(false);
        controls.add(left, BorderLayout.WEST);
        controls.add(activity, BorderLayout.EAST);

        JPanel charts = new JPanel();
        charts.setOpaque(false);
        charts.setLayout(new BoxLayout(charts, BoxLayout.Y_AXIS));
        // 세 차트는 마우스가 가리키는 시각을 함께 쓴다: 한 차트 위에 마우스를 두면 셋 다 그 시각에 선과 값이 나온다.
        HoverSync sync = new HoverSync();
        for (MetricChartCard card : cards()) {
            card.setAlignmentX(Component.LEFT_ALIGNMENT);
            charts.add(card);
            charts.add(Box.createVerticalStrut(12));
            card.chart().onZoomOutFull(this::widenRange);
            card.chart().setSync(sync);
        }
        JLabel hint = new JLabel(HINT);
        hint.setForeground(Theme.TEXT_MUTED);
        hint.putClientProperty(FlatClientProperties.STYLE, "font: -2");
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        charts.add(hint);

        // 왼쪽은 차트 셋, 오른쪽은 요약·디스크·알림 기준 카드
        JPanel main = new JPanel(new BorderLayout(16, 0));
        main.setOpaque(false);
        main.add(charts, BorderLayout.CENTER);
        main.add(side, BorderLayout.EAST);

        add(controls, BorderLayout.NORTH);
        add(main, BorderLayout.CENTER);
    }

    private List<MetricChartCard> cards() {
        return List.of(cpu, memory, network);
    }

    // ── 구간 선택 ───────────────────────────────────────────────

    /** 사용자가 구간 선택의 칸을 눌렀다. */
    private void segmentChosen(int index) {
        if (suppress) {
            return;
        }
        if (index == LIVE) {
            liveMode = true;
            onLiveToggle.accept(true);   // 서버에 SSH 설정이 없으면 MainFrame이 되돌린다(setLiveSelected(false))
            return;
        }
        rangeIndex = index;
        cards().forEach(c -> c.chart().resetZoom());   // 기간이 바뀌면 이전 확대는 의미가 없다
        if (liveMode) {
            liveMode = false;
            onLiveToggle.accept(false);   // 실시간을 끄고, MainFrame이 이 기간을 읽는다
        } else {
            onRangeChanged.run();
        }
    }

    /** 차트를 전체보다 더 축소했다: 더 긴 기간이 있으면 그 기간을 보여 준다. */
    private void widenRange() {
        if (!liveMode && historyRangesAvailable && rangeIndex + 1 < RANGES.size()) {
            ranges.setSelectedIndex(rangeIndex + 1);   // 사용자가 고른 것과 같은 경로로 처리된다
        }
    }

    /** 지금 고른 기록 기간. 실시간을 보는 동안에도 마지막으로 고른 기간이 돌아온다. */
    public Duration selectedRange() {
        return RANGES.get(rangeIndex);
    }

    public boolean isLiveSelected() {
        return liveMode;
    }

    /** 다른 기록 기간을 골랐을 때(실시간에서 넘어온 경우는 {@link #onLiveToggle}로 알린다). */
    public void onRangeChanged(Runnable listener) {
        this.onRangeChanged = listener;
    }

    /** "실시간" 칸을 골랐을 때 true, 실시간에서 다른 기간으로 넘어갈 때 false로 불린다. */
    public void onLiveToggle(Consumer<Boolean> listener) {
        this.onLiveToggle = listener;
    }

    /** 실시간이 스스로 꺼지기까지 남은 시간을 알려 주는 방법을 정한다(안내 문구에 "N분 뒤 자동으로 꺼져요"가 붙는다). */
    public void setLiveRemaining(java.util.function.Supplier<java.util.Optional<Duration>> source) {
        this.liveRemaining = source;
    }

    /**
     * 실시간 안내 문구: {@code SSH로 /proc을 2초마다 읽어요 · 8분 뒤 자동으로 꺼져요}. 남은 시간을 모르면 앞부분만.
     * 남은 시간은 올림해서 분으로 적는다(9분 30초 남았으면 10분). 1분도 안 남았으면 "1분 안에"로 적는다.
     */
    static String liveNote(int intervalSeconds, java.util.Optional<Duration> remaining) {
        String read = "SSH로 /proc을 " + intervalSeconds + "초마다 읽어요";
        if (remaining.isEmpty()) {
            return read;
        }
        long seconds = remaining.get().toSeconds();
        if (seconds < 60) {
            return read + " · 1분 안에 자동으로 꺼져요";
        }
        return read + " · " + ((seconds + 59) / 60) + "분 뒤 자동으로 꺼져요";
    }

    /** 리스너를 호출하지 않고 실시간 여부에 맞게 선택을 바꾼다(실시간이 스스로 꺼졌을 때, 켜지 못했을 때 등). */
    public void setLiveSelected(boolean selected) {
        suppress = true;
        try {
            liveMode = selected;
            ranges.setSelectedIndex(selected ? LIVE : rangeIndex);
        } finally {
            suppress = false;
        }
    }

    /** 실시간 칸을 누를 수 있는지. 서버가 실행 중일 때만 쓸 수 있다. */
    public void setLiveEnabled(boolean enabled, String tooltip) {
        ranges.setSegmentEnabled(LIVE, enabled, tooltip);
    }

    /**
     * 1시간 외의 기간(6시간, 24시간, 7일)을 쓸 수 있는지. 직접 연결 서버는 앱이 켜져 있는 동안 모은 기록이
     * 전부라서 쓸 수 없다. 쓸 수 없는데 그 기간을 보고 있었다면 1시간으로 돌아간다.
     */
    public void setHistoryRangesAvailable(boolean available) {
        historyRangesAvailable = available;
        for (int i = DEFAULT_RANGE + 1; i < RANGES.size(); i++) {
            ranges.setSegmentEnabled(i, available, DIRECT_ONLY);
        }
        if (!available && rangeIndex > DEFAULT_RANGE) {
            rangeIndex = DEFAULT_RANGE;
            if (!liveMode) {
                suppress = true;
                try {
                    ranges.setSelectedIndex(DEFAULT_RANGE);
                } finally {
                    suppress = false;
                }
            }
        }
    }

    // ── 쓰는 쪽이 부르는 메서드: 데이터 ─────────────────────────

    public void setStatus(String text, boolean error) {
        status.setText(text == null || text.isEmpty() ? " " : com.infradesk.ui.IpPrivacy.mask(text));
        status.setForeground(error ? Theme.WARNING : Theme.TEXT_MUTED);
    }

    public void showLoading() {
        liveSamples.clear();
        cards().forEach(c -> c.showEmpty("불러오는 중…"));
        side.showSummary("—", "—", "—");
    }

    public void showMessage(String message) {
        liveSamples.clear();
        cards().forEach(c -> c.showEmpty(message));
        side.showSummary("—", "—", "—");
    }

    /** 디스크 사용량(서버 정보에서 읽은 값). {@code readAt}은 읽은 시각이다. */
    public void setDisk(long used, long total, java.time.Instant readAt) {
        side.setDisk(used, total, readAt);
    }

    /** 디스크 값을 읽지 못했거나 아직 없을 때의 안내. */
    public void setDiskMessage(String message) {
        side.setDiskMessage(message);
    }

    /** 알림 설정을 바꿨거나 처음 읽었다: "알림 기준" 카드를 갱신한다. */
    public void setAlert(AlertSettings settings) {
        side.setAlert(settings);
        // CPU 알림이 켜져 있을 때만 CPU 차트에 기준선(점선)을 그린다. 꺼져 있으면 알림이 나가지 않으니 선도 없다.
        cpu.chart().setAlertThreshold(MetricsSidePanel.cpuThreshold(settings));
    }

    /** "알림 기준" 카드의 [설정에서 바꾸기]를 눌렀다. */
    public void onOpenAlertSettings(Runnable listener) {
        side.onOpenSettings(listener);
    }

    /** 기록(과거) 데이터. 고른 기간의 점들이다. */
    public void showHistory(Metrics m) {
        cpu.show(List.of(m.cpuPercent()), "데이터가 아직 없어요");
        memory.show(List.of(m.memoryPercent()), "메모리 메트릭이 없어요 (OCI 에이전트 확인)");
        network.show(List.of(m.networkInBps(), m.networkOutBps()), "데이터가 아직 없어요");
        side.showSummary(MetricSummary.percentLine(m.cpuPercent(), false),
                MetricSummary.percentLine(m.memoryPercent(), false),
                MetricSummary.rateLine(m.networkInBps(), m.networkOutBps(), false));
        setStatus("OCI 모니터링 · " + INTERVALS.get(rangeIndex) + " 간격 · " + LocalTime.now().format(MINUTE) + " 갱신", false);
    }

    /** 실시간 샘플 하나를 추가하고 다시 그린다. */
    public void addLive(ProcStats.Sample s) {
        liveSamples.add(s);
        if (liveSamples.size() > LIVE_CAPACITY) {
            liveSamples.removeFirst();
        }
        List<Metrics.Sample> c = new ArrayList<>();
        List<Metrics.Sample> m = new ArrayList<>();
        List<Metrics.Sample> in = new ArrayList<>();
        List<Metrics.Sample> out = new ArrayList<>();
        for (ProcStats.Sample x : liveSamples) {
            c.add(new Metrics.Sample(x.time(), x.cpuPercent()));
            m.add(new Metrics.Sample(x.time(), x.memoryPercent()));
            in.add(new Metrics.Sample(x.time(), x.rxBytesPerSec()));
            out.add(new Metrics.Sample(x.time(), x.txBytesPerSec()));
        }
        cpu.show(List.of(c), "");
        memory.show(List.of(m), "");
        network.show(List.of(in, out), "");
        side.showSummary(MetricSummary.percentLine(c, true), MetricSummary.percentLine(m, true),
                MetricSummary.rateLine(in, out, true));
        setStatus(liveNote(ProcStats.INTERVAL_SECONDS, liveRemaining.get()), false);
    }

    public void clearLive() {
        liveSamples.clear();
    }

    // ── 테스트에서 화면의 상태를 읽는 데 쓴다 ───────────────────

    /** 구간 선택에서 지금 선택된 칸 번호. */
    int segmentIndex() {
        return ranges.getSelectedIndex();
    }

    SegmentedControl rangeControl() {
        return ranges;
    }

    MetricChartCard cpuCard() {
        return cpu;
    }

    MetricChartCard memoryCard() {
        return memory;
    }

    MetricChartCard networkCard() {
        return network;
    }

    String statusText() {
        return status.getText();
    }

    MetricsSidePanel side() {
        return side;
    }
}
