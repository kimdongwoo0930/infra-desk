package com.infradesk.ui.metrics;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.core.Metrics;
import com.infradesk.ssh.ProcStats;
import com.infradesk.ui.Theme;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;

/** "모니터링" section: CPU, memory and network cards plus the live (SSH) toggle. */
public class MetricsPanel extends JPanel {

    /** Keep five minutes of live samples. */
    private static final int LIVE_CAPACITY = 5 * 60 / ProcStats.INTERVAL_SECONDS;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final MetricCard cpu = MetricCard.of(MetricKind.CPU);
    private final MetricCard memory = MetricCard.of(MetricKind.MEMORY);
    private final MetricCard network = MetricCard.of(MetricKind.NETWORK);
    private final JLabel status = new JLabel(" ");
    private final JToggleButton live = new JToggleButton("실시간 (SSH)");
    private final List<ProcStats.Sample> liveSamples = new ArrayList<>();
    private Consumer<Boolean> onLiveToggle = on -> { };
    private boolean suppressToggle;

    public MetricsPanel() {
        super(new BorderLayout(0, 10));
        setOpaque(false);

        JLabel title = new JLabel("모니터링");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        status.setForeground(Theme.TEXT_MUTED);
        status.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        live.putClientProperty(FlatClientProperties.STYLE,
                "arc: 999; background: #00000000; borderWidth: 1; borderColor: #4E5157;"
                        + " selectedBackground: #1F3A24; selectedForeground: #7FD184; font: -1");
        live.setToolTipText("서버 상세를 보는 동안 SSH로 /proc을 2초마다 읽어요");
        live.setFocusable(true);
        live.addActionListener(e -> {
            if (!suppressToggle) {
                onLiveToggle.accept(live.isSelected());
            }
        });

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);
        right.add(status);
        right.add(live);
        header.add(title, BorderLayout.WEST);
        header.add(right, BorderLayout.EAST);

        JPanel cards = new JPanel(new GridLayout(1, 3, 12, 0));
        cards.setOpaque(false);
        cards.add(cpu);
        cards.add(memory);
        cards.add(network);
        cards.setBorder(BorderFactory.createEmptyBorder());

        add(header, BorderLayout.NORTH);
        add(cards, BorderLayout.CENTER);
    }

    /** A card was clicked: the user wants a bigger, zoomable chart of that metric. */
    public void onExpand(Consumer<MetricKind> listener) {
        cpu.onClick(() -> listener.accept(MetricKind.CPU));
        memory.onClick(() -> listener.accept(MetricKind.MEMORY));
        network.onClick(() -> listener.accept(MetricKind.NETWORK));
    }

    public void onLiveToggle(Consumer<Boolean> listener) {
        this.onLiveToggle = listener;
    }

    /** Sets the toggle without firing the listener. */
    public void setLiveSelected(boolean selected) {
        suppressToggle = true;
        live.setSelected(selected);
        suppressToggle = false;
    }

    public void setLiveEnabled(boolean enabled, String tooltip) {
        live.setEnabled(enabled);
        live.setToolTipText(tooltip);
    }

    public void setStatus(String text, boolean error) {
        status.setText(text == null || text.isEmpty() ? " " : com.infradesk.ui.IpPrivacy.mask(text));
        status.setForeground(error ? Theme.WARNING : Theme.TEXT_MUTED);
    }

    public void showLoading() {
        liveSamples.clear();
        for (MetricCard c : new MetricCard[] {cpu, memory, network}) {
            c.showEmpty("불러오는 중…");
        }
    }

    public void showMessage(String message) {
        liveSamples.clear();
        for (MetricCard c : new MetricCard[] {cpu, memory, network}) {
            c.showEmpty(message);
        }
    }

    /** OCI history (one-minute points). */
    public void showHistory(Metrics m) {
        String caption = "최근 1시간";
        cpu.show(List.of(m.cpuPercent()), caption, false, "데이터가 아직 없어요");
        memory.show(List.of(m.memoryPercent()), caption, false, "메모리 메트릭이 없어요 (OCI 에이전트 확인)");
        network.show(List.of(m.networkInBps(), m.networkOutBps()), "수신·송신 합계 · " + caption, false, "데이터가 아직 없어요");
        setStatus("OCI 모니터링 · 1분 간격 · " + LocalTime.now().format(TIME) + " 갱신", false);
    }

    /** Appends one live sample and redraws. */
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
        String caption = "실시간 · " + ProcStats.INTERVAL_SECONDS + "초 간격";
        cpu.show(List.of(c), caption, true, "");
        memory.show(List.of(m), caption, true, "");
        network.show(List.of(in, out), "수신·송신 합계 · " + caption, true, "");
        setStatus("SSH 실시간 · " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")), false);
    }

    public void clearLive() {
        liveSamples.clear();
    }
}
