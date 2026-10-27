package com.infradesk.ui.terminal;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.core.Server;
import com.infradesk.ssh.ExecResult;
import com.infradesk.ui.IpPrivacy;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;
import com.infradesk.ui.components.RoundedPanel;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/**
 * "여러 서버에서 실행"의 결과 카드 (디자인 v2): 패널 안에서 서버별로 성공·실패와 걸린 시간, 출력 첫 줄을 보여 주고,
 * 실패한 서버에는 [다시]가 붙는다. 행을 누르면 전체 출력이 펼쳐진다. 예전의 별도 결과 창을 대신한다.
 *
 * <p>결과는 도착하는 대로 채워지고, 새로 실행하면 카드가 새 결과로 바뀐다(가장 최근 한 번만 보여 준다).
 */
final class BatchResultView extends RoundedPanel {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JLabel title = new JLabel(" ");
    private final JLabel summary = new JLabel(" ");
    private final JPanel rows = new JPanel();
    private final Map<String, Row> byServer = new LinkedHashMap<>();
    private Consumer<Server> onRetry = server -> { };

    BatchResultView() {
        super(new BorderLayout(), new java.awt.Color(0x1E1F22), Theme.DIVIDER, Theme.ARC_CARD);
        title.setFont(Theme.monoFont(12f));
        title.setForeground(Theme.TEXT_MUTED);
        title.setMinimumSize(new Dimension(0, 0));
        summary.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        JPanel head = new JPanel(new BorderLayout(8, 0));
        head.setOpaque(false);
        head.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        head.add(title, BorderLayout.CENTER);
        head.add(summary, BorderLayout.EAST);
        rows.setOpaque(false);
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        add(head, BorderLayout.NORTH);
        add(rows, BorderLayout.CENTER);
        setVisible(false);
    }

    /** [다시]를 눌렀을 때 그 서버에 같은 명령을 다시 보내는 일. */
    void onRetry(Consumer<Server> listener) {
        this.onRetry = listener;
    }

    /** 새 실행을 시작한다: 이전 결과를 지우고 서버마다 "실행 중" 행을 만든다. */
    void start(String command, List<Server> targets) {
        title.setText(CLOCK.format(LocalTime.now()) + " · " + command);
        title.setToolTipText(command);
        rows.removeAll();
        byServer.clear();
        for (Server s : targets) {
            Row row = new Row(s);
            byServer.put(s.id(), row);
            rows.add(row);
        }
        setVisible(true);
        refreshSummary();
        revalidate();
        repaint();
    }

    /** 한 서버를 다시 실행 중으로 돌린다([다시]를 눌렀을 때). */
    void markRunning(Server server) {
        Row row = byServer.get(server.id());
        if (row != null) {
            row.showRunning();
            refreshSummary();
        }
    }

    /** 서버 하나의 결과가 도착했다(EDT에서). */
    void setResult(Server server, ExecResult result, long millis) {
        Row row = byServer.get(server.id());
        if (row != null) {
            row.showResult(result, millis);
            refreshSummary();
        }
    }

    private void refreshSummary() {
        int ok = 0;
        int failed = 0;
        int running = 0;
        for (Row row : byServer.values()) {
            switch (row.state) {
                case RUNNING -> running++;
                case OK -> ok++;
                case FAILED -> failed++;
            }
        }
        summary.setText("<html>" + summaryHtml(ok, failed, running) + "</html>");
        revalidate();
    }

    // ── 문구 (순수 함수, 테스트용으로 열어 둔다) ────────────────

    /** 오른쪽 위 요약: "2 성공 · 1 실패", 실행 중이면 "1 실행 중 · 2 성공". 성공은 초록, 실패는 빨강. */
    static String summaryHtml(int ok, int failed, int running) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (running > 0) {
            parts.add("<font color='" + Theme.hex(Theme.TEXT_MUTED) + "'>" + running + " 실행 중</font>");
        }
        if (ok > 0) {
            parts.add("<font color='" + Theme.hex(Theme.RUNNING_BADGE_TEXT) + "'>" + ok + " 성공</font>");
        }
        if (failed > 0) {
            parts.add("<font color='" + Theme.hex(Theme.DANGER_TEXT) + "'>" + failed + " 실패</font>");
        }
        return String.join(" · ", parts);
    }

    /** 걸린 시간: 10초 미만은 소수 한 자리("0.3초"), 그 이상은 정수 초, 1분이 넘으면 "1분 5초". */
    static String durationText(long millis) {
        if (millis < 10_000) {
            return String.format(java.util.Locale.ROOT, "%.1f초", Math.max(0, millis) / 1000.0);
        }
        long seconds = Math.round(millis / 1000.0);
        return seconds < 60 ? seconds + "초" : seconds / 60 + "분" + (seconds % 60 == 0 ? "" : " " + seconds % 60 + "초");
    }

    /** 행에 보이는 한 줄: 출력의 첫 비어 있지 않은 줄, 실패면 오류 문구, 아무것도 없으면 "(출력 없음)". */
    static String previewLine(ExecResult r) {
        if (r.error() != null) {
            return IpPrivacy.mask(r.error());
        }
        for (String line : r.output().split("\\R")) {
            if (!line.isBlank()) {
                return line.strip();
            }
        }
        return "(출력 없음)";
    }

    /** 펼쳤을 때 보이는 전체 출력. 길어서 잘렸으면 그렇다고 적고, 오류가 있으면 끝에 붙인다. */
    static String fullText(ExecResult r) {
        StringBuilder sb = new StringBuilder(r.output());
        if (r.truncated()) {
            sb.append("\n… (출력이 길어 뒷부분을 생략했어요)");
        }
        if (r.error() != null) {
            sb.append(sb.isEmpty() ? "" : "\n\n").append("⚠ ").append(IpPrivacy.mask(r.error()));
        }
        return sb.isEmpty() ? "(출력 없음)" : sb.toString();
    }

    /** 성공으로 칠지: 종료 코드가 0이고 오류가 없을 때. */
    static boolean succeeded(ExecResult r) {
        return r.succeeded();
    }

    // ── 행 ──────────────────────────────────────────────────────

    private enum State { RUNNING, OK, FAILED }

    private final class Row extends JPanel {
        private final Server server;
        private final JLabel mark = new JLabel("…");
        private final JLabel name = new JLabel();
        private final JLabel duration = new JLabel(" ");
        private final JLabel preview = new JLabel("실행 중…");
        private final JButton retry = Buttons.secondary("다시", null);
        private final JTextArea output = new JTextArea();
        private final JScrollPane outputScroll;
        private State state = State.RUNNING;
        private ExecResult result;

        Row(Server server) {
            super(new BorderLayout(10, 0));
            this.server = server;
            setOpaque(false);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.DIVIDER),
                    BorderFactory.createEmptyBorder(10, 12, 10, 10)));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            mark.setForeground(Theme.WARNING);
            mark.setFont(mark.getFont().deriveFont(java.awt.Font.BOLD, 14f));
            mark.setPreferredSize(new Dimension(16, 16));
            mark.setVerticalAlignment(javax.swing.SwingConstants.TOP);
            name.setText(server.name());
            name.setFont(name.getFont().deriveFont(java.awt.Font.BOLD));
            duration.setForeground(Theme.TEXT_MUTED);
            duration.putClientProperty(FlatClientProperties.STYLE, "font: -1");
            preview.setFont(Theme.monoFont(11.5f));
            preview.setForeground(Theme.TEXT_MUTED);
            preview.setMinimumSize(new Dimension(0, preview.getPreferredSize().height));

            JPanel first = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
            first.setOpaque(false);
            duration.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
            first.add(name);
            first.add(duration);
            JPanel lines = new JPanel(new BorderLayout(0, 3));
            lines.setOpaque(false);
            lines.add(first, BorderLayout.NORTH);
            lines.add(preview, BorderLayout.CENTER);

            retry.setVisible(false);
            retry.addActionListener(e -> {
                markRunning(server);
                onRetry.accept(server);
            });
            JPanel east = new JPanel(new java.awt.GridBagLayout());
            east.setOpaque(false);
            east.add(retry);

            output.setEditable(false);
            output.setLineWrap(true);
            output.setWrapStyleWord(false);
            output.setFont(Theme.monoFont(11.5f));
            output.setBackground(Theme.TERMINAL_BG);
            output.setForeground(new java.awt.Color(0xC9CCD1));
            output.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
            outputScroll = new JScrollPane(output);
            outputScroll.setBorder(BorderFactory.createLineBorder(Theme.DIVIDER));
            outputScroll.setPreferredSize(new Dimension(10, 140));
            outputScroll.setVisible(false);

            JPanel top = new JPanel(new BorderLayout(10, 0));
            top.setOpaque(false);
            top.add(mark, BorderLayout.WEST);
            top.add(lines, BorderLayout.CENTER);
            top.add(east, BorderLayout.EAST);
            add(top, BorderLayout.NORTH);
            add(outputScroll, BorderLayout.CENTER);
            ((BorderLayout) getLayout()).setVgap(8);

            // 행을 누르면 전체 출력을 펼치고 접는다(실행이 끝난 뒤에만).
            MouseAdapter toggle = new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (result != null) {
                        outputScroll.setVisible(!outputScroll.isVisible());
                        BatchResultView.this.revalidate();
                        BatchResultView.this.repaint();
                    }
                }
            };
            addMouseListener(toggle);
            top.addMouseListener(toggle);
            lines.addMouseListener(toggle);
            preview.addMouseListener(toggle);
            name.addMouseListener(toggle);
        }

        void showRunning() {
            state = State.RUNNING;
            result = null;
            mark.setText("…");
            mark.setForeground(Theme.WARNING);
            duration.setText(" ");
            preview.setText("실행 중…");
            preview.setForeground(Theme.TEXT_MUTED);
            retry.setVisible(false);
            outputScroll.setVisible(false);
        }

        void showResult(ExecResult r, long millis) {
            result = r;
            boolean ok = succeeded(r);
            state = ok ? State.OK : State.FAILED;
            mark.setText(ok ? "✓" : "✗");
            mark.setForeground(ok ? Theme.RUNNING_DOT : Theme.DANGER_TEXT);
            duration.setText(durationText(millis));
            preview.setText(previewLine(r));
            preview.setToolTipText(preview.getText());
            preview.setForeground(ok ? Theme.TEXT_MUTED : Theme.DANGER_TEXT);
            retry.setVisible(!ok);
            output.setText(fullText(r));
            output.setCaretPosition(0);
        }

        /** 가로는 담는 칸의 폭만큼(긴 출력 줄이 칸을 넓히지 않게), 세로는 내용만큼. */
        @Override
        public Dimension getPreferredSize() {
            return new Dimension(10, super.getPreferredSize().height);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, super.getPreferredSize().height);
        }
    }

    /** 가로는 담는 열의 폭만큼, 세로는 내용만큼. */
    @Override
    public Dimension getPreferredSize() {
        return new Dimension(10, super.getPreferredSize().height);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, super.getPreferredSize().height);
    }
}
