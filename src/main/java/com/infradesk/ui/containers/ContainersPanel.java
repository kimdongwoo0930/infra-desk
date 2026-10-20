package com.infradesk.ui.containers;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.ssh.Container;
import com.infradesk.ssh.DockerCommands;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;
import com.infradesk.ui.components.RoundedPanel;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/** 서버 상세의 "컨테이너" 섹션: SSH로 보는 Docker 컨테이너와 로그/재시작/정지/시작. */
public class ContainersPanel extends JPanel {

    private static final int ROW = 30;

    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JLabel status = new JLabel(" ");
    private final JLabel message = new JLabel(" ", SwingConstants.CENTER);
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private final JButton refresh = Buttons.icon("refresh", "컨테이너 새로고침", 28);
    private final JButton logs = Buttons.secondary("로그", "file");
    private final JButton shell = Buttons.secondary("셸", "terminal");
    private final JButton console = Buttons.secondary("DB 콘솔", "database");
    private final JButton restart = Buttons.secondary("재시작", "refresh");
    private final JButton stop = Buttons.danger("정지", "stop");
    private final JButton start = Buttons.secondary("시작", "play");
    private Runnable onRefresh = () -> { };
    private BiConsumer<Container, DockerCommands.Action> onAction = (c, a) -> { };
    private Consumer<Container> onLogs = c -> { };
    private BiConsumer<Container, DockerCommands.Console> onShell = (c, console) -> { };
    private boolean busy;

    public ContainersPanel() {
        super(new BorderLayout(0, 10));
        setOpaque(false);

        JLabel title = new JLabel("컨테이너");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        status.setForeground(Theme.TEXT_MUTED);
        status.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setOpaque(false);
        left.add(title);
        left.add(status);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(logs);
        right.add(shell);
        right.add(console);
        right.add(restart);
        right.add(stop);
        right.add(start);
        right.add(refresh);
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(left, BorderLayout.WEST);
        header.add(right, BorderLayout.EAST);

        table.setRowHeight(ROW);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.setBackground(Theme.PANEL_BG);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());
        table.getColumnModel().getColumn(0).setCellRenderer(new NameRenderer());
        int[] widths = {220, 200, 170, 70, 150, 220};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        table.getColumnModel().getColumn(3).setCellRenderer(padded(SwingConstants.RIGHT, 0, 16));
        for (int col : new int[] {1, 2, 4, 5}) {
            table.getColumnModel().getColumn(col).setCellRenderer(padded(SwingConstants.LEFT, 10, 8));
        }
        table.getTableHeader().setReorderingAllowed(false);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(Theme.PANEL_BG);

        message.setForeground(Theme.TEXT_MUTED);
        body.setOpaque(false);
        body.add(scroll, "table");
        body.add(message, "message");
        RoundedPanel card = new RoundedPanel(new BorderLayout(), Theme.PANEL_BG, Theme.DIVIDER, Theme.ARC_CARD);
        card.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        card.add(body);

        add(header, BorderLayout.NORTH);
        add(card, BorderLayout.CENTER);

        refresh.addActionListener(e -> onRefresh.run());
        logs.addActionListener(e -> selected().ifPresent(onLogs));
        shell.addActionListener(e -> selected().ifPresent(c -> onShell.accept(c, null)));
        console.addActionListener(e -> selected().ifPresent(c ->
                DockerCommands.Console.forImage(c.image()).ifPresent(k -> onShell.accept(c, k))));
        restart.addActionListener(e -> selected().ifPresent(c -> onAction.accept(c, DockerCommands.Action.RESTART)));
        stop.addActionListener(e -> selected().ifPresent(c -> onAction.accept(c, DockerCommands.Action.STOP)));
        start.addActionListener(e -> selected().ifPresent(c -> onAction.accept(c, DockerCommands.Action.START)));
        showMessage("—", null);
    }

    public void onRefresh(Runnable r) {
        this.onRefresh = r;
    }

    public void onAction(BiConsumer<Container, DockerCommands.Action> c) {
        this.onAction = c;
    }

    public void onLogs(Consumer<Container> c) {
        this.onLogs = c;
    }

    /** 컨테이너 안에서 터미널을 연다: 셸({@code console}이 null) 또는 데이터베이스 콘솔. */
    public void onShell(BiConsumer<Container, DockerCommands.Console> c) {
        this.onShell = c;
    }

    public void showLoading() {
        setBusy(true, "불러오는 중…");
        if (model.rows.isEmpty()) {
            message.setText("불러오는 중…");
            cards.show(body, "message");
            resize(3);
        }
    }

    /** @param statusText 제목 옆의 짧은 텍스트. 없으면 null */
    public void showMessage(String text, String statusText) {
        model.set(List.of());
        message.setText(text);
        cards.show(body, "message");
        setBusy(false, statusText);
        refresh.setEnabled(!"—".equals(text) || statusText != null);
        resize(3);
    }

    public void showContainers(List<Container> containers, String statusText) {
        String selectedId = selected().map(Container::id).orElse(null);
        model.set(containers);
        if (containers.isEmpty()) {
            message.setText("컨테이너가 없어요");
            cards.show(body, "message");
            resize(3);
        } else {
            cards.show(body, "table");
            resize(containers.size());
            for (int i = 0; i < containers.size(); i++) {
                if (containers.get(i).id().equals(selectedId)) {
                    table.setRowSelectionInterval(i, i);
                }
            }
        }
        setBusy(false, statusText);
    }

    public void setBusy(boolean busy, String statusText) {
        this.busy = busy;
        status.setText(statusText == null ? " " : statusText);
        status.setForeground(Theme.TEXT_MUTED);
        refresh.setEnabled(!busy);
        updateButtons();
    }

    public void showError(String text) {
        status.setText(text);
        status.setForeground(Theme.DANGER_TEXT);
        busy = false;
        refresh.setEnabled(true);
        updateButtons();
    }

    private void resize(int rows) {
        int visible = Math.max(2, Math.min(rows, 8));
        body.setPreferredSize(new Dimension(100, table.getTableHeader().getPreferredSize().height + visible * ROW + 2));
        revalidate();
    }

    /** 섹션을 내용 높이만큼만 유지한다. 그렇지 않으면 BoxLayout이 페이지를 채우도록 늘린다. */
    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    private static DefaultTableCellRenderer padded(int alignment, int left, int right) {
        DefaultTableCellRenderer r = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                setBorder(BorderFactory.createEmptyBorder(0, left, 0, right));
                return this;
            }
        };
        r.setHorizontalAlignment(alignment);
        return r;
    }

    private java.util.Optional<Container> selected() {
        int row = table.getSelectedRow();
        return row < 0 || row >= model.rows.size() ? java.util.Optional.empty() : java.util.Optional.of(model.rows.get(row));
    }

    private void updateButtons() {
        var c = selected();
        boolean has = c.isPresent() && !busy;
        boolean running = c.map(Container::isRunning).orElse(false);
        logs.setEnabled(has);
        shell.setEnabled(has && running);
        var db = c.flatMap(k -> DockerCommands.Console.forImage(k.image()));
        console.setVisible(db.isPresent());
        console.setEnabled(has && running && db.isPresent());
        console.setText(db.map(k -> k.label + " 콘솔").orElse("DB 콘솔"));
        restart.setEnabled(has && running);
        stop.setVisible(!c.isPresent() || running);
        stop.setEnabled(has && running);
        start.setVisible(c.isPresent() && !running);
        start.setEnabled(has && !running);
        String hint = c.isPresent() ? null : "컨테이너를 선택하세요";
        for (JButton b : new JButton[] {logs, shell, restart, stop, start}) {
            b.setToolTipText(b.isEnabled() ? null : hint);
        }
        if (c.isPresent() && !running) {
            shell.setToolTipText("실행 중인 컨테이너에만 들어갈 수 있어요");
        }
    }

    private static final class Model extends AbstractTableModel {
        private List<Container> rows = List.of();

        void set(List<Container> rows) {
            this.rows = List.copyOf(rows);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 6;
        }

        @Override
        public String getColumnName(int c) {
            return new String[] {"이름", "이미지", "상태", "CPU", "메모리", "포트"}[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            Container x = rows.get(r);
            return switch (c) {
                case 0 -> x;
                case 1 -> x.image();
                case 2 -> x.status();
                case 3 -> x.cpuPercent() < 0 ? "—" : String.format(Locale.ROOT, "%.1f%%", x.cpuPercent());
                case 4 -> x.memUsage().isEmpty() ? "—" : x.memUsage().split(" / ")[0];
                default -> x.ports().isEmpty() ? "—" : compactPorts(x.ports());
            };
        }
    }

    /** "0.0.0.0:80->80/tcp, :::80->80/tcp" → "80→80". IPv4/IPv6 중복은 제거한다. */
    static String compactPorts(String ports) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String p : ports.split(",\\s*")) {
            String s = p.strip().replaceAll("^(?:[0-9.]+|\\[?:::?\\]?|::):", "").replace("/tcp", "").replace("->", "→");
            out.add(s);
        }
        return String.join(", ", out);
    }

    private static final class NameRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            Container c = (Container) v;
            super.getTableCellRendererComponent(t, c.name(), sel, focus, row, col);
            setIcon(new Dot(c.isRunning() ? Theme.RUNNING_DOT : Theme.TEXT_MUTED, c.isRunning()));
            setIconTextGap(8);
            setToolTipText(c.state() + " · " + c.shortId());
            setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 0));
            return this;
        }
    }

    private record Dot(Color color, boolean filled) implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            if (filled) {
                g2.fillOval(x, y, 8, 8);
            } else {
                g2.setStroke(new java.awt.BasicStroke(1.5f));
                g2.drawOval(x + 1, y + 1, 6, 6);
            }
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 8;
        }

        @Override
        public int getIconHeight() {
            return 8;
        }
    }
}
