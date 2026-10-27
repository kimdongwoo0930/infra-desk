package com.infradesk.ui.overview;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.ssh.Container;
import com.infradesk.ssh.DockerCommands;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;
import com.infradesk.ui.components.RoundedPanel;
import com.infradesk.ui.containers.ContainerFormat;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * 개요 탭의 컨테이너 요약 (디자인 v2): "컨테이너  실행 중 3 / 4 · 15:31:28 기준"과 컨테이너마다 한 줄(상태 점, 이름과
 * 이미지, 상태, CPU, 메모리, 작업 아이콘). 실행 중이면 [로그] [셸] [재시작], 멈췄으면 [시작]이 붙는다.
 *
 * <p>목록은 컨테이너 탭이 읽은 것을 그대로 받는다(서버에 따로 묻지 않는다). 작업을 눌렀을 때 하는 일(확인 창, 실제
 * 실행)은 컨테이너 탭과 같은 곳이 처리한다. 너무 많으면 앞의 {@value #MAX_ROWS}개만 보이고 나머지는 "모두 보기"로 본다.
 */
public final class ContainerSummaryCard extends RoundedPanel {

    /** 한 번에 보여 주는 컨테이너 수. */
    static final int MAX_ROWS = 6;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final int ROW_HEIGHT = 56;

    private final JLabel title = new JLabel("컨테이너");
    private final JLabel subtitle = new JLabel(" ");
    private final JPanel rows = new JPanel();
    private final JLabel message = new JLabel(" ");
    private Consumer<Container> onLogs = c -> { };
    private Consumer<Container> onShell = c -> { };
    private BiConsumer<Container, DockerCommands.Action> onAction = (c, a) -> { };
    private Runnable onViewAll = () -> { };

    public ContainerSummaryCard() {
        super(new BorderLayout(), Theme.PANEL_BG, Theme.DIVIDER, Theme.ARC_CARD);
        setAlignmentX(LEFT_ALIGNMENT);
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 15f));
        subtitle.setForeground(Theme.TEXT_MUTED);
        subtitle.setBorder(BorderFactory.createEmptyBorder(2, 10, 0, 0));
        JButton viewAll = Buttons.ghost("모두 보기");
        viewAll.setForeground(Theme.ACCENT_TEXT);
        viewAll.addActionListener(e -> onViewAll.run());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setOpaque(false);
        left.add(title);
        left.add(subtitle);
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.setBorder(BorderFactory.createEmptyBorder(12, 20, 12, 12));
        head.add(left, BorderLayout.CENTER);
        head.add(viewAll, BorderLayout.EAST);

        rows.setOpaque(false);
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        message.setForeground(Theme.TEXT_MUTED);
        message.setBorder(BorderFactory.createEmptyBorder(16, 20, 20, 20));
        add(head, BorderLayout.NORTH);
        add(rows, BorderLayout.CENTER);
        showMessage("불러오는 중…");
    }

    public void onLogs(Consumer<Container> listener) {
        this.onLogs = listener;
    }

    public void onShell(Consumer<Container> listener) {
        this.onShell = listener;
    }

    public void onAction(BiConsumer<Container, DockerCommands.Action> listener) {
        this.onAction = listener;
    }

    /** "모두 보기"를 눌렀을 때(컨테이너 탭으로). */
    public void onViewAll(Runnable listener) {
        this.onViewAll = listener;
    }

    /** 컨테이너를 보여 줄 수 없을 때의 안내("서버가 실행 중일 때 표시돼요", "SSH 설정 후 표시돼요" 등). */
    public void showMessage(String text) {
        subtitle.setText(" ");
        rows.removeAll();
        message.setText(text);
        message.setAlignmentX(LEFT_ALIGNMENT);
        rows.add(message);
        revalidate();
        repaint();
    }

    /** 컨테이너 목록을 보여 준다. {@code at}은 목록을 읽은 시각이다. */
    public void setContainers(List<Container> containers, Instant at) {
        subtitle.setText(headerText(containers, at));
        rows.removeAll();
        if (containers.isEmpty()) {
            message.setText("컨테이너가 없어요");
            message.setAlignmentX(LEFT_ALIGNMENT);
            rows.add(message);
        }
        List<Container> ordered = ordered(containers);
        for (int i = 0; i < Math.min(ordered.size(), MAX_ROWS); i++) {
            rows.add(new Row(ordered.get(i), i > 0));
        }
        revalidate();
        repaint();
    }

    // ── 글자 (순수 함수, 테스트용으로 열어 둔다) ────────────────

    /** 제목 옆 글자: "실행 중 3 / 4 · 15:31:28 기준". */
    static String headerText(List<Container> containers, Instant at) {
        long running = containers.stream().filter(Container::isRunning).count();
        return "실행 중 " + running + " / " + containers.size() + (at == null ? "" : " · " + CLOCK.format(at) + " 기준");
    }

    /** 실행 중인 것을 앞에, 멈춘 것을 뒤에 둔다(각각 원래 순서는 그대로). */
    static List<Container> ordered(List<Container> containers) {
        List<Container> result = new ArrayList<>();
        containers.stream().filter(Container::isRunning).forEach(result::add);
        containers.stream().filter(c -> !c.isRunning()).forEach(result::add);
        return result;
    }

    /** 상태 글자: 실행 중이면 Docker 문구 그대로("Up 14 days"), 멈췄으면 "멈춤 · 3일 전"(종료 코드는 뺀다). */
    static String statusText(Container c) {
        return ContainerFormat.status(c.status()).replaceFirst("^멈춤 \\(-?\\d+\\)", "멈춤");
    }

    /** 메모리 칸: 쓴 양만("46MiB / 23.4GiB" → "46 MiB"). 실행 중이 아니거나 모르면 줄표. */
    static String memoryText(Container c) {
        if (!c.isRunning() || c.memUsage() == null || c.memUsage().isBlank()) {
            return "—";
        }
        String used = c.memUsage().split("/")[0].strip();
        return used.replaceFirst("^([0-9.]+)([A-Za-z]+)$", "$1 $2");
    }

    /** CPU 칸: "12.8%". 실행 중이 아니거나 모르면 줄표. */
    static String cpuText(Container c) {
        return !c.isRunning() || c.cpuPercent() < 0 ? "—" : String.format(java.util.Locale.ROOT, "%.1f%%", c.cpuPercent());
    }

    // ── 행 ──────────────────────────────────────────────────────

    private final class Row extends JPanel {

        Row(Container c, boolean divider) {
            super(new BorderLayout(14, 0));
            setOpaque(false);
            setBorder(BorderFactory.createCompoundBorder(
                    divider ? BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.DIVIDER) : BorderFactory.createEmptyBorder(1, 0, 0, 0),
                    BorderFactory.createEmptyBorder(0, 20, 0, 16)));
            boolean running = c.isRunning();

            JLabel dot = new JLabel(new Dot(running));
            JLabel name = new JLabel(c.name());
            name.setFont(name.getFont().deriveFont(java.awt.Font.BOLD));
            JLabel image = new JLabel(c.image());
            image.setFont(Theme.monoFont(11.5f));
            image.setForeground(Theme.TEXT_MUTED);
            // 긴 이미지 이름이 줄을 넓히지 않고 "…"로 끝나게 한다.
            name.setMinimumSize(new Dimension(0, name.getPreferredSize().height));
            image.setMinimumSize(new Dimension(0, image.getPreferredSize().height));
            JPanel names = new JPanel(new BorderLayout(0, 3));
            names.setOpaque(false);
            names.add(name, BorderLayout.NORTH);
            names.add(image, BorderLayout.CENTER);
            JPanel nameBox = new JPanel(new java.awt.GridBagLayout());
            nameBox.setOpaque(false);
            java.awt.GridBagConstraints fill = new java.awt.GridBagConstraints();
            fill.weightx = 1;
            fill.fill = java.awt.GridBagConstraints.HORIZONTAL;
            nameBox.add(names, fill);

            JLabel status = cell(statusText(c), 150, SwingConstants.LEFT);
            status.setForeground(running ? Theme.TEXT_MUTED : Theme.WARNING);
            JLabel cpu = cell(cpuText(c), 64, SwingConstants.RIGHT);
            JLabel memory = cell(memoryText(c), 84, SwingConstants.RIGHT);
            JPanel numbers = new JPanel();
            numbers.setOpaque(false);
            numbers.setLayout(new BoxLayout(numbers, BoxLayout.X_AXIS));
            numbers.add(status);
            numbers.add(cpu);
            numbers.add(memory);

            JPanel actions = new JPanel(new java.awt.GridBagLayout());
            actions.setOpaque(false);
            actions.setPreferredSize(new Dimension(124, ROW_HEIGHT));
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
            buttons.setOpaque(false);
            if (running) {
                buttons.add(iconButton("file", "로그 보기", () -> onLogs.accept(c)));
                buttons.add(iconButton("terminal", "셸 열기", () -> onShell.accept(c)));
                buttons.add(iconButton("refresh", "재시작", () -> onAction.accept(c, DockerCommands.Action.RESTART)));
            } else {
                JButton start = Buttons.secondary("시작", null);
                start.addActionListener(e -> onAction.accept(c, DockerCommands.Action.START));
                buttons.add(start);
            }
            actions.add(buttons);

            JPanel center = new JPanel(new BorderLayout(14, 0));
            center.setOpaque(false);
            center.add(nameBox, BorderLayout.CENTER);
            center.add(numbers, BorderLayout.EAST);
            add(dot, BorderLayout.WEST);
            add(center, BorderLayout.CENTER);
            add(actions, BorderLayout.EAST);
        }

        private JLabel cell(String text, int width, int align) {
            JLabel l = new JLabel(text, align);
            l.setPreferredSize(new Dimension(width, 20));
            l.setMinimumSize(new Dimension(width, 20));
            l.setMaximumSize(new Dimension(width, 20));
            return l;
        }

        private JButton iconButton(String icon, String tooltip, Runnable action) {
            JButton b = Buttons.icon(icon, tooltip, 28);
            b.addActionListener(e -> action.run());
            return b;
        }

        /** 가로는 카드 폭만큼, 세로는 줄 높이로 고정한다. */
        @Override
        public Dimension getPreferredSize() {
            return new Dimension(10, ROW_HEIGHT);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, ROW_HEIGHT);
        }
    }

    /** 상태 점: 실행 중이면 초록으로 채우고, 멈췄으면 테두리만. 색만으로 구분하지 않도록 모양도 다르다. */
    private record Dot(boolean running) implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (running) {
                g2.setColor(Theme.RUNNING_DOT);
                g2.fillOval(x, y, 9, 9);
            } else {
                g2.setColor(Theme.TEXT_MUTED);
                g2.drawOval(x, y, 8, 8);
            }
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 10;
        }

        @Override
        public int getIconHeight() {
            return 10;
        }
    }
}
