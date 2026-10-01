package com.infradesk.ui.metrics;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.core.Metrics;
import com.infradesk.ui.Async;
import com.infradesk.ui.Theme;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.Timer;

/**
 * 메트릭 카드를 클릭해서 여는, 서버 하나의 메트릭 하나에 대한 크고 확대 가능한 차트. 클라우드 서버는
 * 기간(1시간~7일)을 바꿀 수 있고, 불러온 기간 밖으로 축소하면 다음 기간으로 넘어간다.
 * 열려 있는 동안 1분마다 다시 불러온다.
 */
public final class MetricChartDialog extends JDialog {

    private record Range(String label, Duration duration) {
    }

    private static final List<Range> RANGES = List.of(
            new Range("1시간", Duration.ofHours(1)),
            new Range("6시간", Duration.ofHours(6)),
            new Range("24시간", Duration.ofHours(24)),
            new Range("7일", Duration.ofDays(7)));
    private static final int REFRESH_MS = 60_000;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String HINT = "휠: 확대·축소   드래그: 이동   더블클릭: 처음 범위";

    private final MetricKind kind;
    private final boolean ranges;
    private final Function<Duration, Metrics> loader;
    private final ZoomChartPanel chart;
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private final JLabel message = new JLabel("", SwingConstants.CENTER);
    private final JLabel current = new JLabel(" ");
    private final JLabel status = new JLabel(" ");
    private final JButton reset = com.infradesk.ui.components.Buttons.secondary("처음 범위", null);
    private final List<JToggleButton> rangeButtons = new ArrayList<>();
    private final Timer refreshTimer;
    private int rangeIndex;
    private boolean shown;
    private long request;

    /**
     * @param serverName 제목 접두어
     * @param ranges     소스가 다른 기간도 제공할 수 있는지. false면 가진 것만 보여준다(예: SSH로 수집한 샘플)
     * @param loader     기간에 대한 블로킹 메트릭 소스. EDT 밖에서 실행된다
     */
    public MetricChartDialog(Frame owner, String serverName, MetricKind kind, boolean ranges, Function<Duration, Metrics> loader) {
        super(owner, serverName + " · " + kind.title(), false);
        this.kind = kind;
        this.ranges = ranges;
        this.loader = loader;
        this.chart = new ZoomChartPanel(kind);

        getContentPane().setBackground(Theme.PANEL_BG);
        setLayout(new BorderLayout(0, 8));
        ((JComponent) getContentPane()).setBorder(BorderFactory.createEmptyBorder(12, 16, 10, 16));

        add(topBar(), BorderLayout.NORTH);
        message.setForeground(Theme.TEXT_MUTED);
        body.setOpaque(false);
        body.add(chart, "chart");
        body.add(message, "message");
        add(body, BorderLayout.CENTER);
        add(bottomBar(), BorderLayout.SOUTH);

        chart.onZoomOutFull(this::widenRange);
        chart.onZoomChanged(zoomed -> reset.setEnabled(zoomed));
        reset.addActionListener(e -> chart.resetZoom());
        reset.setEnabled(false);

        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        refreshTimer = new Timer(REFRESH_MS, e -> load(false));
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                refreshTimer.stop();
                request++;
            }
        });

        setSize(new Dimension(960, 560));
        setMinimumSize(new Dimension(560, 360));
        setLocationRelativeTo(owner);
    }

    /** 다이얼로그를 보여주고 불러오기를 시작한다. */
    public void open() {
        setVisible(true);
        startLoading();
    }

    /** 첫 데이터를 불러오고 1분마다 계속 다시 불러온다. 스냅샷 도구가 창을 띄우지 않고 쓸 수 있도록 public. */
    public void startLoading() {
        load(true);
        refreshTimer.start();
    }

    private JPanel topBar() {
        JPanel bar = new JPanel(new BorderLayout(12, 0));
        bar.setOpaque(false);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);
        if (ranges) {
            ButtonGroup group = new ButtonGroup();
            for (int i = 0; i < RANGES.size(); i++) {
                int index = i;
                JToggleButton b = new JToggleButton(RANGES.get(i).label());
                b.putClientProperty(FlatClientProperties.STYLE,
                        "arc: 999; background: #00000000; borderWidth: 1; borderColor: #4E5157;"
                                + " selectedBackground: #2E436E; selectedForeground: #DFE1E5; font: -1");
                b.setFocusable(true);
                b.setSelected(i == rangeIndex);
                b.addActionListener(e -> selectRange(index));
                group.add(b);
                rangeButtons.add(b);
                left.add(b);
            }
        } else {
            JLabel only = new JLabel("수집한 최근 데이터");
            only.setForeground(Theme.TEXT_MUTED);
            left.add(only);
        }
        current.setForeground(Theme.TEXT);
        current.putClientProperty(FlatClientProperties.STYLE, "font: +2");
        bar.add(left, BorderLayout.WEST);
        bar.add(current, BorderLayout.EAST);
        return bar;
    }

    private JPanel bottomBar() {
        JPanel bar = new JPanel(new BorderLayout(12, 0));
        bar.setOpaque(false);
        JLabel hint = new JLabel(HINT);
        hint.setForeground(Theme.TEXT_MUTED);
        hint.putClientProperty(FlatClientProperties.STYLE, "font: -2");
        status.setForeground(Theme.TEXT_MUTED);
        status.putClientProperty(FlatClientProperties.STYLE, "font: -2");
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);
        right.add(status);
        right.add(reset);
        bar.add(hint, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private void selectRange(int index) {
        if (index == rangeIndex) {
            return;
        }
        rangeIndex = index;
        if (ranges) {
            rangeButtons.get(index).setSelected(true);
        }
        shown = false;
        chart.resetZoom();
        load(true);
    }

    /** 불러온 전체보다 더 축소했다: 더 긴 기간이 있으면 그것을 보여준다. */
    private void widenRange() {
        if (ranges && rangeIndex + 1 < RANGES.size()) {
            selectRange(rangeIndex + 1);
        }
    }

    /** @param showLoading 제자리 갱신(새로고침) 대신 차트를 "불러오는 중"으로 바꾼다(기간 변경) */
    private void load(boolean showLoading) {
        long id = ++request;
        Duration range = RANGES.get(rangeIndex).duration();
        if (showLoading) {
            message.setText("불러오는 중…");
            cards.show(body, "message");
        }
        Async.run(() -> loader.apply(range), metrics -> {
            if (id != request) {
                return;
            }
            List<List<Metrics.Sample>> data = kind.select(metrics);
            if (data.stream().allMatch(List::isEmpty)) {
                message.setText("데이터가 아직 없어요");
                cards.show(body, "message");
                current.setText(" ");
                return;
            }
            chart.setData(data);
            shown = true;
            cards.show(body, "chart");
            current.setText(currentText(data));
            status.setText(LocalTime.now().format(CLOCK) + " 갱신");
        }, err -> {
            if (id != request) {
                return;
            }
            if (showLoading || !shown) {
                message.setText("메트릭을 불러오지 못했어요: " + Async.message(err));
                cards.show(body, "message");
            } else {
                status.setText("갱신 실패 · 이전 데이터");
            }
        });
    }

    private String currentText(List<List<Metrics.Sample>> data) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.size(); i++) {
            List<Metrics.Sample> d = data.get(i);
            if (d.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("   ");
            }
            if (kind.series().size() > 1) {
                sb.append(kind.series().get(i).name()).append(' ');
            }
            sb.append(kind.format().apply(d.getLast().value()));
        }
        return sb.toString();
    }
}
