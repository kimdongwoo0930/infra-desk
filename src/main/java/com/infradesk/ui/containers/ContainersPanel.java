package com.infradesk.ui.containers;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.ssh.Container;
import com.infradesk.ssh.DockerCommands;
import com.infradesk.ui.Icons;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;
import com.infradesk.ui.components.FilterChip;
import com.infradesk.ui.components.Meter;
import com.infradesk.ui.components.RoundedPanel;
import com.infradesk.ui.containers.ContainerRowActions.RowAction;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;

/**
 * "컨테이너" 탭 위쪽의 컨테이너 표 (디자인 v2): SSH로 보는 Docker 컨테이너와, 행마다 있는 작업 아이콘
 * (셸, DB 콘솔, 재시작, 정지. 멈춘 행은 시작 하나). 로그는 아이콘이 없다: 행을 클릭하면 아래에 뜬다.
 *
 * <pre>
 *  [전체 5] [실행 중 4] [멈춤 1] [이름·이미지로 찾기]              docker ps · 15:31:28 기준  [⟳]
 *  ┌ 이름 ─ 이미지 ─ 상태 ─ CPU ─────── 메모리 ─ 네트워크 받음/보냄 ─ 포트 ─────── 작업 ─┐
 *  │ ● nginx  nginx:1.27  Up 9 days  ▬▬▬▬ 18.0%  258 MiB  48 MB / 52 MB  80→80      >_ ↻ ■ │
 * </pre>
 *
 * <p>이 패널은 서버에 접속하지 않는다. 눌린 작업은 {@link #onAction}, {@link #onShell},
 * {@link #onRefresh}로 알리기만 하고, 실제 일(확인 창, SSH 명령)은 {@code MainFrame}이 한다.
 */
public class ContainersPanel extends JPanel {

    private static final int ROW = 40;
    private static final int HEADER_HEIGHT = 32;
    private static final int ACTIONS_COLUMN = 7;
    private static final int CONTROL_HEIGHT = 28;

    private final Model model = new Model();
    private final JTable table = new JTable(model) {
        /** 마우스를 올린 곳에 따라 툴팁을 정한다: 작업 아이콘은 이름, 이름 칸은 상태와 id. */
        @Override
        public String getToolTipText(MouseEvent e) {
            int row = rowAtPoint(e.getPoint());
            int col = columnAtPoint(e.getPoint());
            if (row < 0 || row >= model.rows.size()) {
                return null;
            }
            Container c = model.rows.get(row);
            if (col == ACTIONS_COLUMN) {
                RowAction action = actionAt(e.getPoint(), row, col);
                return action == null ? null : action.label;
            }
            return col == 0 ? c.state() + " · " + c.shortId() : null;
        }
    };
    private final JLabel status = new JLabel(" ");
    private final JLabel message = new JLabel(" ", SwingConstants.CENTER);
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private final JButton refresh = Buttons.icon("refresh", "컨테이너 새로고침", CONTROL_HEIGHT);
    private final FilterChip allChip = new FilterChip("전체");
    private final FilterChip runningChip = new FilterChip("실행 중");
    private final FilterChip stoppedChip = new FilterChip("멈춤");
    private final JTextField search = new JTextField();
    private final CpuCell cpuCell = new CpuCell();
    private final ActionsCell actionsCell = new ActionsCell();

    private Runnable onRefresh = () -> { };
    private BiConsumer<Container, DockerCommands.Action> onAction = (c, a) -> { };
    private Consumer<Container> onSelect = c -> { };
    /** 코드가 선택을 바꾸는 중이면 true. 사용자가 고른 것과 구별해서 onSelect를 부르지 않으려는 것이다. */
    private boolean programmatic;
    private BiConsumer<Container, DockerCommands.Console> onShell = (c, console) -> { };
    private boolean busy;
    /** 마우스가 올라가 있는 작업 아이콘(그 아이콘만 배경이 밝아진다). */
    private int hoverRow = -1;
    private RowAction hoverAction;
    /** 받은 컨테이너 전부(거르기 전). */
    private List<Container> all = List.of();
    /** 0 = 전체, 1 = 실행 중, 2 = 멈춤. */
    private int filter;

    public ContainersPanel() {
        super(new BorderLayout(0, 10));
        setOpaque(false);

        add(toolbar(), BorderLayout.NORTH);
        add(card(), BorderLayout.CENTER);
        configureTable();
        wireEvents();
        showMessage("—", null);
    }

    // ── 화면 조립 ───────────────────────────────────────────────

    private JPanel toolbar() {
        ButtonGroup group = new ButtonGroup();
        for (FilterChip chip : new FilterChip[] {allChip, runningChip, stoppedChip}) {
            group.add(chip);
        }
        allChip.setSelected(true);
        search.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "이름·이미지로 찾기");
        search.setPreferredSize(new Dimension(220, CONTROL_HEIGHT));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        left.add(allChip);
        left.add(runningChip);
        left.add(stoppedChip);
        left.add(search);

        status.setForeground(Theme.TEXT_MUTED);
        status.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);
        right.add(status);
        right.add(refresh);

        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private RoundedPanel card() {
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
        return card;
    }

    private void configureTable() {
        table.setRowHeight(ROW);
        table.setShowGrid(false);
        table.setShowHorizontalLines(true);
        table.setGridColor(Theme.DIVIDER);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setFillsViewportHeight(true);
        table.setBackground(Theme.PANEL_BG);
        table.setSelectionBackground(Theme.SELECTION_BG);
        table.setSelectionForeground(Theme.TEXT);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        // 사용자가 행을 고르면(마우스 클릭, 화살표 키) 알린다. 코드가 선택을 바꿀 때는 알리지 않는다.
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !programmatic) {
                selected().ifPresent(c -> onSelect.accept(c));
            }
        });

        // 창이 좁으면 칸이 비례해서 줄어든다. 숫자가 잘리면 안 되는 칸(CPU, 메모리, 네트워크, 작업)은 최소 폭을 지킨다.
        // 이름·이미지·포트는 글자가 길면 끝을 "…"로 줄이면 되어서 더 좁아질 수 있다.
        int[] widths = {150, 170, 140, 140, 80, 170, 140, 150};
        int[] minimums = {110, 100, 120, 130, 64, 150, 110, 140};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
            table.getColumnModel().getColumn(i).setMinWidth(minimums[i]);
        }

        table.getColumnModel().getColumn(0).setCellRenderer(new NameRenderer());
        table.getColumnModel().getColumn(1).setCellRenderer(new TextRenderer(SwingConstants.LEFT, true));
        table.getColumnModel().getColumn(2).setCellRenderer(new StatusRenderer());
        table.getColumnModel().getColumn(3).setCellRenderer((t, v, sel, focus, row, col) -> {
            cpuCell.set(model.rows.get(row), sel);
            return cpuCell;
        });
        table.getColumnModel().getColumn(4).setCellRenderer(new TextRenderer(SwingConstants.RIGHT, false));
        table.getColumnModel().getColumn(5).setCellRenderer(new TextRenderer(SwingConstants.RIGHT, false));
        table.getColumnModel().getColumn(6).setCellRenderer(new TextRenderer(SwingConstants.LEFT, true));
        table.getColumnModel().getColumn(ACTIONS_COLUMN).setCellRenderer((t, v, sel, focus, row, col) -> {
            actionsCell.set(model.rows.get(row), sel, row == hoverRow ? hoverAction : null, busy);
            return actionsCell;
        });

        JTableHeader header = table.getTableHeader();
        header.setReorderingAllowed(false);
        header.setResizingAllowed(false);
        header.setPreferredSize(new Dimension(0, HEADER_HEIGHT));
        header.setDefaultRenderer(new HeaderRenderer());
    }

    private void wireEvents() {
        refresh.addActionListener(e -> onRefresh.run());
        allChip.addActionListener(e -> setFilter(0));
        runningChip.addActionListener(e -> setFilter(1));
        stoppedChip.addActionListener(e -> setFilter(2));
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilter();
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                clicked(e.getPoint());
            }

            @Override
            public void mouseExited(MouseEvent e) {
                setHover(-1, null);
            }
        });
        table.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                int col = table.columnAtPoint(e.getPoint());
                setHover(row, col == ACTIONS_COLUMN ? actionAt(e.getPoint(), row, col) : null);
            }
        });
    }

    // ── 쓰는 쪽이 부르는 메서드 ─────────────────────────────────

    public void onRefresh(Runnable r) {
        this.onRefresh = r;
    }

    public void onAction(BiConsumer<Container, DockerCommands.Action> c) {
        this.onAction = c;
    }

    /** 사용자가 표에서 행을 골랐다(클릭, 화살표 키). 아래 로그를 그 컨테이너로 바꾸는 데 쓴다(로그 아이콘은 없다). */
    public void onSelect(Consumer<Container> c) {
        this.onSelect = c;
    }

    /**
     * 표에서 이 컨테이너의 행을 강조한다(아래 로그 패널에서 다른 컨테이너를 골랐을 때 표가 따라가게).
     * 거르기 때문에 보이지 않는 컨테이너면 강조를 푼다. {@link #onSelect}는 부르지 않는다.
     */
    public void highlight(Container c) {
        programmatic = true;
        try {
            int index = -1;
            for (int i = 0; i < model.rows.size(); i++) {
                if (model.rows.get(i).id().equals(c.id())) {
                    index = i;
                }
            }
            if (index < 0) {
                table.clearSelection();
            } else {
                table.setRowSelectionInterval(index, index);
            }
        } finally {
            programmatic = false;
        }
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

    /** @param statusText 오른쪽 위의 짧은 텍스트. 없으면 null */
    public void showMessage(String text, String statusText) {
        all = List.of();
        programmatic = true;
        try {
            model.set(List.of());
        } finally {
            programmatic = false;
        }
        updateCounts();
        message.setText(text);
        cards.show(body, "message");
        setBusy(false, statusText);
        refresh.setEnabled(!"—".equals(text) || statusText != null);
        resize(3);
    }

    public void showContainers(List<Container> containers, String statusText) {
        all = List.copyOf(containers);
        updateCounts();
        applyFilter();
        setBusy(false, statusText);
    }

    /** 컨테이너 작업을 서버에 보내고 기다리는 중인지. */
    public boolean isBusy() {
        return busy;
    }

    public void setBusy(boolean busy, String statusText) {
        this.busy = busy;
        status.setText(statusText == null ? " " : statusText);
        status.setForeground(Theme.TEXT_MUTED);
        refresh.setEnabled(!busy);
        table.repaint();   // 요청 중에는 작업 아이콘이 흐려진다
    }

    public void showError(String text) {
        status.setText(text);
        status.setForeground(Theme.DANGER_TEXT);
        busy = false;
        refresh.setEnabled(true);
        table.repaint();
    }

    // ── 거르기 ──────────────────────────────────────────────────

    private void setFilter(int filter) {
        this.filter = filter;
        applyFilter();
    }

    /** 칩(전체/실행 중/멈춤)과 검색어로 보여 줄 컨테이너를 고른다. 고른 행은 가능하면 유지한다. */
    private void applyFilter() {
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        List<Container> shown = all.stream()
                .filter(c -> filter == 0 || (filter == 1 && c.isRunning()) || (filter == 2 && !c.isRunning()))
                .filter(c -> query.isEmpty() || c.name().toLowerCase(Locale.ROOT).contains(query)
                        || c.image().toLowerCase(Locale.ROOT).contains(query))
                .toList();
        String selectedId = selected().map(Container::id).orElse(null);
        programmatic = true;
        try {
            model.set(shown);
            restoreSelection(shown, selectedId);
        } finally {
            programmatic = false;
        }
        if (all.isEmpty()) {
            message.setText("컨테이너가 없어요");
            cards.show(body, "message");
            resize(3);
            return;
        }
        if (shown.isEmpty()) {
            message.setText("조건에 맞는 컨테이너가 없어요");
            cards.show(body, "message");
            resize(3);
            return;
        }
        cards.show(body, "table");
        resize(shown.size());
    }

    /** 목록이 바뀐 뒤에도 고른 행을 가능하면 유지한다. */
    private void restoreSelection(List<Container> shown, String selectedId) {
        for (int i = 0; i < shown.size(); i++) {
            if (shown.get(i).id().equals(selectedId)) {
                table.setRowSelectionInterval(i, i);
            }
        }
    }

    /** 사용자가 행을 고른 것처럼 선택한다. 테스트용. */
    void selectRowAsUser(int row) {
        table.setRowSelectionInterval(row, row);
    }

    /** 지금 강조된(선택된) 컨테이너. 테스트용. */
    Optional<Container> highlighted() {
        return selected();
    }

    private void updateCounts() {
        allChip.setCount(all.size());
        runningChip.setCount((int) all.stream().filter(Container::isRunning).count());
        stoppedChip.setCount((int) all.stream().filter(c -> !c.isRunning()).count());
    }

    /** 지금 표에 보이는 줄 수(거르기 뒤). 테스트용. */
    int visibleRows() {
        return model.rows.size();
    }

    // ── 작업 아이콘 ─────────────────────────────────────────────

    private void clicked(Point p) {
        int row = table.rowAtPoint(p);
        int col = table.columnAtPoint(p);
        if (row < 0 || col != ACTIONS_COLUMN) {
            return;
        }
        RowAction action = actionAt(p, row, col);
        if (action != null) {
            perform(model.rows.get(row), action);
        }
    }

    /** 칸 안에서 점 {@code p} 아래에 있는 작업 아이콘. 없으면 null. */
    private RowAction actionAt(Point p, int row, int col) {
        if (row < 0 || row >= model.rows.size()) {
            return null;
        }
        Rectangle cell = table.getCellRect(row, col, false);
        return ContainerRowActions.hit(model.rows.get(row), p.x - cell.x, p.y - cell.y, cell.width, cell.height);
    }

    /** 눌린 작업을 알린다. 요청 중에는 무시한다(같은 동작을 연달아 보내지 않게). 테스트용으로 열어 둔다. */
    void perform(Container c, RowAction action) {
        if (busy) {
            return;
        }
        switch (action) {
            case SHELL -> onShell.accept(c, null);
            case CONSOLE -> DockerCommands.Console.forImage(c.image()).ifPresent(k -> onShell.accept(c, k));
            case RESTART -> onAction.accept(c, DockerCommands.Action.RESTART);
            case STOP -> onAction.accept(c, DockerCommands.Action.STOP);
            case START -> onAction.accept(c, DockerCommands.Action.START);
        }
    }

    private void setHover(int row, RowAction action) {
        if (row == hoverRow && action == hoverAction) {
            return;
        }
        hoverRow = row;
        hoverAction = action;
        table.setCursor(Cursor.getPredefinedCursor(action == null ? Cursor.DEFAULT_CURSOR : Cursor.HAND_CURSOR));
        table.repaint();
    }

    // ── 크기 ────────────────────────────────────────────────────

    private void resize(int rows) {
        int visible = Math.max(2, Math.min(rows, 8));
        body.setPreferredSize(new Dimension(100, HEADER_HEIGHT + visible * (ROW + 1) + 2));
        revalidate();
    }

    private java.util.Optional<Container> selected() {
        int row = table.getSelectedRow();
        return row < 0 || row >= model.rows.size() ? Optional.empty() : Optional.of(model.rows.get(row));
    }

    // ── 표 모델 ─────────────────────────────────────────────────

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
            return 8;
        }

        @Override
        public String getColumnName(int c) {
            return new String[] {"이름", "이미지", "상태", "CPU", "메모리", "네트워크 받음/보냄", "포트", "작업"}[c];
        }

        /** 글자 칸은 화면에 보이는 글자를, 그림으로 그리는 칸(이름, CPU, 작업)은 컨테이너를 돌려준다. */
        @Override
        public Object getValueAt(int r, int c) {
            Container x = rows.get(r);
            return switch (c) {
                case 0, 3, 7 -> x;
                case 1 -> x.image();
                case 2 -> ContainerFormat.status(x.status());
                case 4 -> ContainerFormat.memory(x.memUsage());
                case 5 -> ContainerFormat.network(x.netIo());
                default -> x.ports().isEmpty() ? "—" : compactPorts(x.ports());
            };
        }
    }

    /** "0.0.0.0:80->80/tcp, :::80->80/tcp" → "80→80". IPv4/IPv6 중복은 제거한다. */
    static String compactPorts(String ports) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String p : ports.split(",\\s*")) {
            String s = p.strip().replaceAll("^(?:[0-9.]+|\\[?:::?\\]?|::):", "").replace("/tcp", "").replace("->", "→");
            out.add(s);
        }
        return String.join(", ", out);
    }

    // ── 칸 그리기 ───────────────────────────────────────────────

    /** 헤더: 배경 없이 흐린 글자와 아래 구분선만. 숫자 열은 오른쪽 정렬. */
    private static final class HeaderRenderer implements TableCellRenderer {
        private final JLabel label = new JLabel();

        HeaderRenderer() {
            label.setOpaque(true);
            label.setBackground(Theme.PANEL_BG);
            label.setForeground(Theme.TEXT_MUTED);
            label.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            label.setText(String.valueOf(v));
            boolean right = col == 4 || col == 5 || col == ACTIONS_COLUMN;
            label.setHorizontalAlignment(right ? SwingConstants.RIGHT : SwingConstants.LEFT);
            label.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.DIVIDER),
                    BorderFactory.createEmptyBorder(0, col == 0 ? 28 : 8, 0, col == ACTIONS_COLUMN ? 16 : 8)));
            return label;
        }
    }

    /** 글자 칸. 이미지와 포트는 고정폭. */
    private static final class TextRenderer extends DefaultTableCellRenderer {
        private final boolean mono;

        TextRenderer(int alignment, boolean mono) {
            this.mono = mono;
            setHorizontalAlignment(alignment);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            super.getTableCellRendererComponent(t, v, sel, focus, row, col);
            setFont(mono ? Theme.monoFont(11.5f) : t.getFont());
            setForeground(sel ? Theme.TEXT : mono ? Theme.TEXT_SECONDARY : Theme.TEXT);
            setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
            return this;
        }
    }

    /** 상태 칸: 멈춘 컨테이너는 노란 글자로 눈에 띄게. */
    private final class StatusRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            super.getTableCellRendererComponent(t, v, sel, focus, row, col);
            boolean running = model.rows.get(row).isRunning();
            setForeground(sel ? Theme.TEXT : running ? Theme.TEXT_SECONDARY : Theme.WARNING_BADGE_TEXT);
            setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
            return this;
        }
    }

    /** 이름 칸: 상태 점(실행 중 채움, 멈춤 속 빔)과 굵은 이름. */
    private static final class NameRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            Container c = (Container) v;
            super.getTableCellRendererComponent(t, c.name(), sel, focus, row, col);
            setFont(t.getFont().deriveFont(java.awt.Font.BOLD));
            setForeground(Theme.TEXT);
            setIcon(new Dot(c.isRunning() ? Theme.RUNNING_DOT : Theme.TEXT_MUTED, c.isRunning()));
            setIconTextGap(10);
            setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 0));
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
                g2.setStroke(new BasicStroke(1.5f));
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

    /** CPU 칸: 막대(트랙 + 사용률)와 오른쪽의 숫자. 실행 중이 아니면 빈 트랙과 줄표. */
    private static final class CpuCell extends JComponent {
        private static final int BAR_WIDTH = 76;
        private static final int LEFT_PAD = 8;
        private static final int RIGHT_PAD = 12;

        private Container container;
        private boolean selected;

        void set(Container container, boolean selected) {
            this.container = container;
            this.selected = selected;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(selected ? Theme.SELECTION_BG : Theme.PANEL_BG);
                g2.fillRect(0, 0, getWidth(), getHeight());

                double percent = container.cpuPercent();
                int y = (getHeight() - 6) / 2;
                g2.setColor(Theme.DIVIDER);
                g2.fillRoundRect(LEFT_PAD, y, BAR_WIDTH, 6, 6, 6);
                if (percent >= 0) {
                    int fill = (int) Math.round(BAR_WIDTH * Math.min(100, percent) / 100.0);
                    if (fill > 0) {
                        g2.setColor(percent >= Meter.WARN_AT ? Theme.WARNING_BAR : Theme.ACCENT_LINE);
                        g2.fillRoundRect(LEFT_PAD, y, fill, 6, 6, 6);
                    }
                }
                // 숫자는 칸 오른쪽 끝에 붙이되, 막대 끝(+간격)보다 왼쪽으로는 오지 않게 한다(칸이 좁을 때 겹치지 않게).
                String text = ContainerFormat.cpu(percent);
                g2.setFont(getFont() == null ? g2.getFont() : getFont());
                FontMetrics fm = g2.getFontMetrics();
                g2.setColor(percent >= 0 ? Theme.TEXT : Theme.TEXT_MUTED);
                int textX = Math.max(LEFT_PAD + BAR_WIDTH + 8, getWidth() - RIGHT_PAD - fm.stringWidth(text));
                g2.drawString(text, textX, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            } finally {
                g2.dispose();
            }
        }
    }

    /** 작업 칸: 상태에 맞는 아이콘들을 오른쪽 끝에 붙여 그린다. 위치 계산은 {@link ContainerRowActions}가 한다. */
    private static final class ActionsCell extends JComponent {
        private Container container;
        private boolean selected;
        private RowAction hover;
        private boolean dimmed;

        void set(Container container, boolean selected, RowAction hover, boolean dimmed) {
            this.container = container;
            this.selected = selected;
            this.hover = hover;
            this.dimmed = dimmed;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(selected ? Theme.SELECTION_BG : Theme.PANEL_BG);
                g2.fillRect(0, 0, getWidth(), getHeight());
                if (dimmed) {
                    g2.setComposite(java.awt.AlphaComposite.SrcOver.derive(0.4f));
                }
                List<RowAction> actions = ContainerRowActions.actionsFor(container);
                List<Rectangle> boxes = ContainerRowActions.boxes(actions, getWidth(), getHeight());
                for (int i = 0; i < actions.size(); i++) {
                    paintAction(g2, actions.get(i), boxes.get(i));
                }
            } finally {
                g2.dispose();
            }
        }

        private void paintAction(Graphics2D g2, RowAction action, Rectangle box) {
            boolean hovered = action == hover;
            if (hovered) {
                g2.setColor(Theme.HOVER_BG);
                g2.fillRoundRect(box.x, box.y, box.width, box.height, 12, 12);
            }
            // 정지는 위험한 동작이라 붉게, 나머지는 보조 글자색
            Color color = action == RowAction.STOP ? Theme.DANGER_TEXT : Theme.TEXT_SECONDARY;
            Icons.get(action.icon, 16, color).paintIcon(this, g2, box.x + (box.width - 16) / 2,
                    box.y + (box.height - 16) / 2);
        }
    }
}
